package com.novelfa.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.RoomBookRepository
import com.novelfa.reader.library.LibraryViewModel
import com.novelfa.reader.settings.PinManager
import com.novelfa.reader.settings.PrefsStorePinPrefs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Import / Library / Navigation audit probes (2026-11-25).
 *
 * Five findings, ranked — each test asserts the CORRECT behavior, so
 * each one FAILS on the current code (that failure IS the
 * demonstration). No main sources are touched here; the parent runs
 * verification (Gradle is not run from this task: journal lock).
 *
 * Already-fixed bugs are NOT re-probed here: the instant-read
 * back-stop race (OpeningScreen gates on currentDestination ==
 * OPENING, c1941cf0), the CBZ picker fix, the md copy crash
 * (c1941cf0). Already-listed defects are NOT re-reported either
 * (DB-07 batch-duplicate stale snapshot, DB-12 COPY missing-file
 * repair, DB-13 in-place totalChapters=0 restore, DB-14 relink,
 * DB-24 isSourceAvailable permission-only).
 *
 * 1. overwriteDuplicates_failedImport_keepsOldBook — CONFIRMED.
 *    LibraryViewModel.kt:682 removeBook(existing) runs BEFORE
 *    :684 importBook; the catch only counts the failure. A failed
 *    overwrite deletes the old book (row, files, reading record)
 *    and leaves nothing.
 * 2. reopenSameExternalFile_isNotDropped — CONFIRMED.
 *    NavGraph.kt:76 dedups by Uri VALUE for the lifetime of the
 *    composition. Rotation recreates the composition (fresh
 *    lastHandledExternalUri), so the dedup cannot be serving
 *    rotation protection — it only drops a legitimate re-open of
 *    the same file (Telegram -> read -> back -> tap the same file
 *    again: nothing happens).
 * 3. secondWarmIntentWhileOpening_isNotDropped — CONFIRMED.
 *    NavGraph.kt:79 onBookUriConsumed() runs BEFORE the :81
 *    `current != Routes.OPENING` guard, so a second file arriving
 *    while the OpeningScreen is showing is consumed and never
 *    navigated to; OpeningScreen.kt:76 keyless remember keeps the
 *    first file even though lastExternalUri moved on.
 * 4. sendMultipleIntent_opensFirstFile — CONFIRMED.
 *    MainActivity.kt:117-126 extractBookUri handles ACTION_VIEW
 *    and ACTION_SEND only; ACTION_SEND_MULTIPLE falls to else ->
 *    null, so sharing 2+ files opens the library with no import
 *    and no message.
 * 5. failedInPlaceImport_releasesPersistablePermission — CONFIRMED.
 *    Importer.kt:480-485 takes the persistable grant, then
 *    returns straight into importInPlaceContent OUTSIDE the
 *    try whose catch (:1100) cleans up the copy flow — and no
 *    catch releases the grant (release happens only in removeBook /
 *    relinkBook). Every failed in-place import leaks one grant
 *    toward Android's 128-grant cap.
 *
 * Setup mirrors OpenExternalTest (real ViewModel + Importer +
 * Room) and InstantReadNavigationTest / PdfSearchOverlayTest
 * (createAndroidComposeRule<MainActivity> + activity.setContent,
 * idleFor pump loops). The real NavGraph is used for findings
 * 2-3: both flows never reach the reader (both attempts fail),
 * so the real reader — which cannot run on the JVM — never
 * composes. Finding 1 and 5 never touch Compose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NovelfaApplication::class)
class ImportNavigationAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun viewModel(context: Context): LibraryViewModel {
        val app = context.applicationContext as NovelfaApplication
        val prefs = PrefsStore(context.applicationContext)
        return LibraryViewModel(
            RoomBookRepository(app.db.bookDao()),
            Importer(context, app.db, prefs),
            PinManager(PrefsStorePinPrefs(prefs)),
            cacheDir = context.cacheDir,
            filesDir = context.filesDir,
            prefs = prefs
        )
    }

    private fun pump(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(50)
        }
    }

    /** Polls until a node with this text exists (or the budget runs out). */
    private fun waitForText(text: String, timeoutMs: Long = 20_000, substring: Boolean = false): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                composeRule.onNodeWithText(text, substring = substring).assertExists()
                return true
            } catch (_: Throwable) {
                // Not there yet — keep pumping.
            }
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(50)
        }
        return false
    }

    private fun booksNow(vm: LibraryViewModel) =
        runBlocking {
            withTimeoutOrNull(10_000) { vm.repo.books().first() }
        } ?: emptyList()

    // ---- Finding 1: overwrite deletes before importing ----

    @Test
    fun overwriteDuplicates_failedImport_keepsOldBook() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = viewModel(context)
        val dir = File(context.filesDir, "audit-overwrite").apply { mkdirs() }
        val good = File(dir, "good.md").apply {
            writeText("# Good Book\n\nBody text.\n", Charsets.UTF_8)
        }
        val first = runBlocking { vm.importMultiple(listOf(Uri.fromFile(good))) }
        assertEquals("setup: the good book must import", 1, first.added)
        val bookId = booksNow(vm)
            .first { it.originalFileName == "good.md" }.id
        // A file no parser can claim: binary garbage, unknown extension.
        val bad = File(dir, "bad.bin").apply {
            writeBytes(ByteArray(256) { it.toByte() })
        }
        val summary = runBlocking {
            vm.overwriteDuplicates(
                listOf(Uri.fromFile(bad)),
                existingIds = listOf(bookId)
            )
        }
        assertEquals("the broken file must fail the import", 1, summary.failed)
        // The user confirmed a SWAP, not a delete: the old book —
        // row, file, progress — must survive a failed overwrite.
        assertTrue(
            "old book must survive a failed overwrite",
            booksNow(vm).any { it.id == bookId }
        )
    }

    // ---- Finding 2: same-file re-open is dropped ----

    @Test
    fun reopenSameExternalFile_isNotDropped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "audit-reopen").apply { mkdirs() }
        // Attempt 1: the file does not exist yet, so the open fails
        // and the OpeningScreen shows its Failed state.
        val file = File(dir, "book.md")
        var extUri by mutableStateOf<Uri?>(Uri.fromFile(file))
        composeRule.activity.setContent {
            NavGraph(openBookUri = extUri, onBookUriConsumed = { extUri = null })
        }
        val retry = context.getString(R.string.opening_retry)
        assertTrue("setup: first open must reach the Failed screen", waitForText(retry))
        // Attempt 2: the SAME Uri, now a real (but empty) book.
        // A re-open must re-run the import and fail differently
        // ("Book has no readable chapters").
        // NOTE (2026-11-25, fix verification): the bare pump() +
        // assertExists() below used to be here, and it failed even
        // on the fixed code — pump() only idles the main looper, it
        // does not advance the Compose clock, and assertExists()
        // syncs only to idle, which does not wait for the IO-backed
        // import to finish. waitForText polls (clock-advancing) for
        // up to 20 s, which is what the re-run needs. It MUST use
        // substring = true: the text lives in the Failed screen's
        // detail node ("Book has no readable chapters\nURI: ..."),
        // so an exact match never fires (first version of this
        // edit forgot it and failed for that reason alone).
        file.writeText("", Charsets.UTF_8)
        extUri = Uri.fromFile(file)
        assertTrue(
            "re-open must re-run the import and fail differently",
            waitForText("Book has no readable chapters", substring = true)
        )
    }

    // ---- Finding 3: second warm intent while opening is dropped ----

    @Test
    fun secondWarmIntentWhileOpening_isNotDropped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "audit-second-intent").apply { mkdirs() }
        // File A: an empty .md — opens, fails, stays on OPENING.
        val fileA = File(dir, "a.md").apply {
            writeText("", Charsets.UTF_8)
        }
        var extUri by mutableStateOf<Uri?>(Uri.fromFile(fileA))
        composeRule.activity.setContent {
            NavGraph(openBookUri = extUri, onBookUriConsumed = { extUri = null })
        }
        val retry = context.getString(R.string.opening_retry)
        assertTrue("setup: first open must reach the Failed screen", waitForText(retry))
        // File B: a DIFFERENT file, arriving while the OpeningScreen
        // is still showing. It must be opened (and fail with its own
        // reason), not silently consumed.
        // NOTE (2026-11-25, fix verification): same waitForText
        // reason as Finding 2 — a bare pump() + assertExists()
        // does not give the IO-backed import time to settle.
        val fileB = File(dir, "b.bin").apply {
            writeBytes(ByteArray(256) { it.toByte() })
        }
        extUri = Uri.fromFile(fileB)
        assertTrue(
            "second file must be opened, not silently consumed",
            waitForText("Unsupported book format", substring = true)
        )
    }

    // ---- Finding 4: ACTION_SEND_MULTIPLE is ignored ----

    @Test
    fun sendMultipleIntent_opensFirstFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "audit-sendmulti").apply { mkdirs() }
        val first = File(dir, "first.md").apply {
            writeText("# First\n\nBody.\n", Charsets.UTF_8)
        }
        val second = File(dir, "second.md").apply {
            writeText("# Second\n\nBody.\n", Charsets.UTF_8)
        }
        // Same entry point as a cold-start share (both go through
        // MainActivity.extractBookUri); the warm path is used here
        // so no second activity is needed.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(Uri.fromFile(first), Uri.fromFile(second))
            )
            type = "*/*"
        }
        composeRule.activity.runOnUiThread {
            // onNewIntent is protected: invoke it reflectively.
            val method = MainActivity::class.java.getDeclaredMethod(
                "onNewIntent", Intent::class.java
            ).apply { isAccessible = true }
            method.invoke(composeRule.activity, intent)
        }
        // extractBookUri is the gate for BOTH the cold path (onCreate)
        // and the warm path (onNewIntent -> viewUri): today
        // SEND_MULTIPLE falls to else -> null, so the share is
        // silently dropped. Probe it directly — it is a pure
        // function of the Intent. (The first version of this probe
        // drove the warm path through the rule's activity; its
        // teardown deadlocked ActivityScenario.close() under
        // Robolectric, so the end-to-end shape was abandoned.)
        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBookUri", Intent::class.java
        ).apply { isAccessible = true }
        val result = method.invoke(MainActivity(), intent) as Uri?
        assertEquals(
            "sharing two files must open the first one (extractBookUri returned null)",
            Uri.fromFile(first), result
        )
    }

    // ---- Finding 5: failed in-place import leaks the grant ----

    @Test
    fun failedInPlaceImport_releasesPersistablePermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = viewModel(context)
        val resolver = context.contentResolver
        val uri = Uri.parse("content://com.example.provider/books/broken.pdf")
        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        // Seam check first: Robolectric must actually track
        // persistable grants, or the assertion below would pass
        // vacuously (an empty set is not proof of a release).
        resolver.takePersistableUriPermission(uri, flag)
        assertTrue(
            "setup: the shadow must track persistable grants",
            resolver.persistedUriPermissions.any { it.uri == uri }
        )
        resolver.releasePersistableUriPermission(uri, flag)
        assertTrue(
            "setup: releasing a grant must work",
            resolver.persistedUriPermissions.none { it.uri == uri }
        )
        // A provider that cannot be read: the import takes the
        // persistable grant, then fails.
        val result = runBlocking { vm.openExternal(uri, null) }
        assertTrue(
            "setup: the broken provider must fail the open",
            result is LibraryViewModel.OpenExternalResult.Failed
        )
        // The failed import must release the grant it took: every
        // failed in-place import leaks one until Android's
        // 128-grant cap, after which new in-place imports silently
        // fall back to copies (or fail to take the grant at all).
        assertTrue(
            "failed import must release its persistable grant",
            resolver.persistedUriPermissions.none { it.uri == uri }
        )
    }
}
