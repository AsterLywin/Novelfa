package com.novelfa.reader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2 instrumented acceptance tests: DB-02 (single
 * ReadingRecordStore source, atomic writes, corrupt-file
 * quarantine), DB-03 (per-book DataStore cleanup), DB-01's
 * private-hash registry, and the store purge used on
 * privatize / private delete.
 *
 * These need a real Context (files, DataStore). They are
 * compile-verified in the VM; execution needs a device or
 * emulator, like the other instrumented tests.
 *
 * Test isolation: the record store file and the test book's
 * extras keys are wiped in [cleanup]; the singleton's
 * in-memory cache is per-process, so tests that need a fresh
 * read from disk construct a direct instance (production code
 * always uses getInstance).
 */
@RunWith(AndroidJUnit4::class)
class Phase2StoreTest {

    private lateinit var ctx: Context
    private lateinit var prefs: PrefsStore

    private val bookId = "phase2-test-book"
    private val privateHash = "a".repeat(64)
    private val publicHash = "b".repeat(64)

    private fun recordFile() = File(ctx.filesDir, "reading_records.json")
    private fun recordTmp() = File(ctx.filesDir, "reading_records.json.tmp")
    private fun recordCorrupt() = File(ctx.filesDir, "reading_records.json.corrupt")

    @Before
    fun setup() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        prefs = PrefsStore(ctx)
        cleanup()
    }

    @After
    fun teardown() = runBlocking { cleanup() }

    private suspend fun cleanup() {
        ReadingRecordStore.resetInstanceForTests()
        runCatching { recordFile().delete() }
        runCatching { recordTmp().delete() }
        runCatching { recordCorrupt().delete() }
        runCatching { ReaderExtrasStore(ctx).clearBook(bookId) }
        runCatching { prefs.removeReaderTheme(bookId) }
        runCatching { prefs.removePrivateContentHash(privateHash) }
        runCatching { prefs.removePrivateContentHash(publicHash) }
    }

    private fun record(sha: String, title: String) = ReadingRecord(
        sha256 = sha,
        title = title,
        chapterIndex = 2,
        totalChapters = 10
    )

    // --- DB-02 ---

    @Test
    fun db02_getInstance_returnsSameInstance() {
        val a = ReadingRecordStore.getInstance(ctx)
        val b = ReadingRecordStore.getInstance(ctx.applicationContext)
        assertSame(a, b)
    }

    @Test
    fun db02_persist_writesCompleteJsonFile() = runBlocking {
        val store = ReadingRecordStore.getInstance(ctx)
        store.upsert(record(publicHash, "Public Book"))
        // A fresh read straight from the file (not the cache)
        // must see the record: the atomic rename leaves a
        // complete file, never a torn half-write.
        val bytes = recordFile().readBytes()
        val parsed = ReadingRecord.listFromJson(bytes)
        assertEquals(listOf("Public Book"), parsed.map { it.title })
        // No temp file is left behind after a successful write.
        assertTrue(!recordTmp().exists())
    }

    @Test
    fun db02_corruptFile_isQuarantinedNotSilentlyDropped() {
        recordFile().writeBytes("this is not json {{{".toByteArray(Charsets.UTF_8))
        val fresh = ReadingRecordStore(ctx)
        assertTrue(fresh.all().isEmpty())
        // The evidence is kept, not deleted: the corrupt file
        // moves aside so the next launch starts clean.
        assertTrue(recordCorrupt().exists())
        assertTrue(!recordFile().exists())
    }

    @Test
    fun db02_twoWritersThroughSingleton_doNotLoseRecords() = runBlocking {
        val store = ReadingRecordStore.getInstance(ctx)
        store.upsert(record(publicHash, "Public Book"))
        store.upsert(record(privateHash, "Private Book"))
        assertEquals(
            setOf("Public Book", "Private Book"),
            store.all().map { it.title }.toSet()
        )
    }

    // --- DB-01: purge ---

    @Test
    fun db01_removeMatching_purgesOnlyMatchingRecords() = runBlocking {
        val store = ReadingRecordStore.getInstance(ctx)
        store.upsert(record(publicHash, "Public Book"))
        store.upsert(record(privateHash, "Private Book"))
        assertTrue(store.removeMatching(setOf(privateHash), emptySet()))
        assertEquals(listOf("Public Book"), store.all().map { it.title })
        // Idempotent: a second purge finds nothing.
        assertTrue(!store.removeMatching(setOf(privateHash), emptySet()))
    }

    @Test
    fun db01_removeMatching_byEpubId() = runBlocking {
        val store = ReadingRecordStore.getInstance(ctx)
        store.upsert(record("e".repeat(64), "Private Book").copy(epubId = "epub-private"))
        assertTrue(store.removeMatching(emptySet(), setOf("epub-private")))
        assertTrue(store.all().isEmpty())
    }

    // --- DB-03 ---

    @Test
    fun db03_clearBook_removesAllPerBookKeys() = runBlocking {
        val extras = ReaderExtrasStore(ctx)
        extras.setHighlights(bookId, listOf(TextHighlight(1, 0, 10, "#FF0000")))
        extras.setZoomIndex(bookId, 2)
        extras.toggleBookmark(bookId, 3)
        extras.setCropRect(bookId, 0, CropRect(0.1f, 0.1f, 0.9f, 0.9f))
        extras.setScrollOffset(bookId, 123)
        prefs.setReaderTheme(bookId, "some-theme")

        extras.clearBook(bookId)
        prefs.removeReaderTheme(bookId)

        assertTrue(extras.highlights(bookId).first().isEmpty())
        assertEquals(0, extras.zoomIndex(bookId).first())
        assertTrue(extras.bookmarks(bookId).first().isEmpty())
        assertTrue(extras.cropRects(bookId).first().isEmpty())
        assertEquals(0, extras.scrollOffset(bookId).first())
        assertEquals("", prefs.getReaderTheme(bookId))
    }

    // --- DB-01: confidential private-hash list ---

    @Test
    fun db01_privateHashList_roundTripAndValidation() = runBlocking {
        prefs.addPrivateContentHash(privateHash)
        assertEquals(setOf(privateHash), prefs.privateContentHashesNow())
        // Invalid values are ignored, never stored.
        prefs.addPrivateContentHash("not-a-hash")
        prefs.addPrivateContentHash("g".repeat(64))
        assertEquals(setOf(privateHash), prefs.privateContentHashesNow())
        prefs.removePrivateContentHash(privateHash)
        assertTrue(prefs.privateContentHashesNow().isEmpty())
    }

    @Test
    fun db01_privateHashList_lowercasesHashes() = runBlocking {
        prefs.addPrivateContentHash("B".repeat(64))
        assertEquals(setOf(publicHash), prefs.privateContentHashesNow())
    }
}
