package com.novelfa.reader.data

import com.novelfa.reader.domain.Book
import com.novelfa.reader.domain.ContentHashChange
import com.novelfa.reader.domain.XpContentKeyResolver
import com.novelfa.reader.domain.XpLedger
import com.novelfa.reader.domain.XpStore
import com.novelfa.reader.domain.contentHashChange
import com.novelfa.reader.settings.FakeBookRepository
import com.novelfa.reader.theme.InMemoryRankPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 acceptance tests (JVM): DB-01 (record + ledger privacy
 * filtering), F1/F2 (content-identity rules). The Android-only
 * halves — ReadingRecordStore singleton/atomicity, ReaderExtrasStore
 * clearBook, the private-hash registry, and the BackupService
 * export/apply wiring — live in androidTest (Phase2StoreTest) and
 * are compile-verified here, device-executed later.
 */
class Phase2AcceptanceTest {

    private val privateHash = "a".repeat(64)
    private val publicHash = "b".repeat(64)
    private val oldHash = "c".repeat(64)
    private val newHash = "d".repeat(64)

    private fun record(sha: String, epubId: String, title: String) =
        ReadingRecord(
            sha256 = sha,
            epubId = epubId,
            title = title,
            chapterIndex = 3,
            totalChapters = 10
        )

    // --- DB-01: record filtering ---

    @Test
    fun db01_filter_dropsRecordMatchingPrivateHash() {
        val records = listOf(
            record(privateHash, "epub-private", "Private Book"),
            record(publicHash, "epub-public", "Public Book")
        )
        val kept = filterPrivateRecords(records, setOf(privateHash), emptySet())
        assertEquals(listOf("Public Book"), kept.map { it.title })
    }

    @Test
    fun db01_filter_dropsRecordMatchingPrivateEpubIdEvenWhenHashChanged() {
        // The file changed since the record was captured, so the
        // hash no longer matches — but the publication is the same.
        val records = listOf(record("e".repeat(64), "epub-private", "Private Book"))
        val kept = filterPrivateRecords(records, setOf(privateHash), setOf("epub-private"))
        assertTrue(kept.isEmpty())
    }

    @Test
    fun db01_filter_keepsEverythingWhenNoPrivateKeys() {
        val records = listOf(record(publicHash, "epub-public", "Public Book"))
        assertEquals(records, filterPrivateRecords(records, emptySet(), emptySet()))
    }

    @Test
    fun db01_filter_ignoresBlankKeys() {
        // A record with no keys at all cannot identify a private
        // book; it is kept (it is also unusable for restore, but
        // filtering must not invent an identity for it).
        val records = listOf(ReadingRecord(title = "Keyless"))
        val kept = filterPrivateRecords(records, setOf(privateHash), setOf("epub-x"))
        assertEquals(1, kept.size)
    }

    // --- DB-01: ledger filtering (export + apply) ---

    private fun ledgerWith(privateKey: String, publicKey: String): XpLedger {
        val led = XpLedger()
        led.markCredited(privateKey, 0, 1)
        led.markCredited(privateKey, 1, 1)
        led.markBonusAwarded(privateKey)
        led.markCredited(publicKey, 0, 1)
        return led
    }

    @Test
    fun db01_export_excludesPrivateContentEntriesAndBonus() {
        val led = ledgerWith(XpLedger.contentKeyFor(privateHash), XpLedger.contentKeyFor(publicHash))
        // UUID entries never travel anyway; the private content
        // entry must not travel either, bonus flag included.
        led.markCredited("some-uuid-key", 0, 1)
        val exported = XpLedger.parse(led.serializeContentOnly(setOf(privateHash)))
        assertFalse(exported.isCredited(XpLedger.contentKeyFor(privateHash), 0))
        assertFalse(exported.isBonusAwarded(XpLedger.contentKeyFor(privateHash)))
        assertTrue(exported.isCredited(XpLedger.contentKeyFor(publicHash), 0))
    }

    @Test
    fun db01_export_withoutExclusions_isUnchanged() {
        val led = ledgerWith(XpLedger.contentKeyFor(privateHash), XpLedger.contentKeyFor(publicHash))
        val exported = XpLedger.parse(led.serializeContentOnly())
        assertTrue(exported.isCredited(XpLedger.contentKeyFor(privateHash), 0))
        assertTrue(exported.isCredited(XpLedger.contentKeyFor(publicHash), 0))
    }

    @Test
    fun db01_apply_refusesPrivateContentEntries() {
        val incoming = ledgerWith(XpLedger.contentKeyFor(privateHash), XpLedger.contentKeyFor(publicHash))
        val local = XpLedger()
        local.mergeContentFrom(incoming, setOf(privateHash))
        assertFalse(local.isCredited(XpLedger.contentKeyFor(privateHash), 0))
        assertFalse(local.isBonusAwarded(XpLedger.contentKeyFor(privateHash)))
        assertTrue(local.isCredited(XpLedger.contentKeyFor(publicHash), 0))
    }

    // --- F2: content-hash decision ---

    @Test
    fun f2_decision_firstKnownUnchangedChanged() {
        assertEquals(ContentHashChange.FIRST_KNOWN, contentHashChange(null, newHash))
        assertEquals(ContentHashChange.FIRST_KNOWN, contentHashChange("", newHash))
        assertEquals(ContentHashChange.UNCHANGED, contentHashChange(oldHash, oldHash))
        assertEquals(ContentHashChange.CHANGED, contentHashChange(oldHash, newHash))
    }

    private fun book(
        id: String,
        contentHash: String?,
        chapter: Int = 0,
        opened: String? = null,
        anchor: String? = null
    ) = Book(
        id = id,
        title = "Book $id",
        filePath = "/books/$id.epub",
        totalChapters = 10,
        lastChapterIndex = chapter,
        dateAdded = "2026-01-01T00:00:00Z",
        lastOpenedDate = opened,
        lastAnchor = anchor,
        contentHash = contentHash
    )

    @Test
    fun f2_note_firstKnown_persistsHashAndKeepsProgress() = runBlocking {
        val repo = FakeBookRepository(listOf(book("b1", null, chapter = 4, opened = "2026-01-02T00:00:00Z")))
        assertEquals(ContentHashChange.FIRST_KNOWN, repo.noteContentHash("b1", newHash))
        val after = repo.book("b1")!!
        assertEquals(newHash, after.contentHash)
        assertEquals(4, after.lastChapterIndex)
        assertEquals("2026-01-02T00:00:00Z", after.lastOpenedDate)
    }

    @Test
    fun f2_note_unchanged_isNoop() = runBlocking {
        val repo = FakeBookRepository(listOf(book("b1", oldHash, chapter = 4, opened = "2026-01-02T00:00:00Z")))
        assertEquals(ContentHashChange.UNCHANGED, repo.noteContentHash("b1", oldHash))
        val after = repo.book("b1")!!
        assertEquals(oldHash, after.contentHash)
        assertEquals(4, after.lastChapterIndex)
    }

    @Test
    fun f2_note_changed_updatesHashAndResetsProgress() = runBlocking {
        val repo = FakeBookRepository(
            listOf(book("b1", oldHash, chapter = 9, opened = "2026-01-02T00:00:00Z", anchor = "li:tok"))
        )
        assertEquals(ContentHashChange.CHANGED, repo.noteContentHash("b1", newHash))
        val after = repo.book("b1")!!
        assertEquals(newHash, after.contentHash)
        // New content is never mistaken as completed: progress
        // restarts at chapter 0 with no open date and no anchor.
        assertEquals(0, after.lastChapterIndex)
        assertNull(after.lastOpenedDate)
        assertNull(after.lastAnchor)
    }

    @Test
    fun f2_note_unknownBook_isNoBook() = runBlocking {
        val repo = FakeBookRepository(emptyList())
        assertEquals(ContentHashChange.NO_BOOK, repo.noteContentHash("missing", newHash))
    }

    // --- F1/F2: resolver transfer guard ---

    private fun storeWithUuidEntry(uuid: String): XpStore {
        val store = XpStore(InMemoryRankPrefs())
        val led = XpLedger()
        led.markCredited(uuid, 0, 1)
        store.ledger = led
        return store
    }

    @Test
    fun f1_resolver_transfersWhenStoredHashUnknown() {
        val store = storeWithUuidEntry("book-uuid")
        val key = XpContentKeyResolver(store).resolve("book-uuid", newHash, storedHash = null)
        assertEquals(XpLedger.contentKeyFor(newHash), key)
        assertTrue(store.ledger.isCredited(key, 0))
        assertFalse(store.ledger.isCredited("book-uuid", 0))
    }

    @Test
    fun f1_resolver_transfersWhenStoredHashMatches() {
        val store = storeWithUuidEntry("book-uuid")
        val key = XpContentKeyResolver(store).resolve("book-uuid", newHash, storedHash = newHash)
        assertEquals(XpLedger.contentKeyFor(newHash), key)
        assertTrue(store.ledger.isCredited(key, 0))
    }

    @Test
    fun f2_resolver_refusesTransferWhenContentChanged() {
        // The row recorded oldHash; the fresh hash is newHash, so
        // the UUID entry belongs to the OLD content and must not
        // move to the new content's key — the new content starts
        // from zero, and the old entry stays (lifetime total kept).
        val store = storeWithUuidEntry("book-uuid")
        val resolver = XpContentKeyResolver(store)
        val key = resolver.resolve("book-uuid", newHash, storedHash = oldHash)
        assertEquals(XpLedger.contentKeyFor(newHash), key)
        assertFalse(store.ledger.isCredited(key, 0))
        assertTrue(store.ledger.isCredited("book-uuid", 0))
    }
}
