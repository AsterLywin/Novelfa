package com.novelfa.reader.library

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.novelfa.reader.MainActivity
import com.novelfa.reader.NavGraph
import com.novelfa.reader.NovelfaApplication
import com.novelfa.reader.R
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.RoomBookRepository
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
 * 2026-11-26 bug round: typing one letter in the library search
 * hides the keyboard by itself. Cause: the hero card ("continue
 * reading") disappears on the first keystroke (hero is shown only
 * while the query is blank), the keyless lazy header items shift
 * one index, and the focused search TextField is disposed and
 * recreated unfocused — so the IME hides. EXPECTED TO FAIL until
 * the header items get stable keys.
 *
 * Single test only: like ImportNavigationAuditTest, the class as a
 * whole hangs in ActivityScenario.close() teardown under
 * Robolectric, so run this method singly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = NovelfaApplication::class,
    // Tall viewport: the library header stack (profile + greeting +
    // stats + hero + sort + tabs) is ~550dp, so on the default
    // 320x470 viewport the search field starts below the fold and
    // uncomposed in the lazy grid. A tall viewport composes it.
    qualifiers = "w800dp-h1200dp"
)
class LibrarySearchKeyboardTest {

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

    /** Polls until a node with this text exists (or the budget runs out). */
    private fun waitForText(text: String, timeoutMs: Long = 20_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                composeRule.onNodeWithText(text).assertExists()
                return true
            } catch (_: Throwable) {
                // Not there yet — keep pumping.
            }
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun typingFirstLetter_keepsSearchFieldFocused() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as NovelfaApplication
        val vm = viewModel(context)

        // Seed one in-progress book so the hero card shows: two
        // chapters (a one-chapter book with lastOpenedDate counts as
        // completed, not in progress), then mark it opened.
        val dir = File(context.filesDir, "kb-hero").apply { mkdirs() }
        val file = File(dir, "book.md").apply {
            writeText("# Book\n\nBody text.\n\n## Ch2\n\nMore text.\n")
        }
        val summary = runBlocking { vm.importMultiple(listOf(Uri.fromFile(file))) }
        assertEquals(1, summary.added)
        val book = runBlocking {
            withTimeoutOrNull(10_000) { vm.repo.books().first() }
        }!!.first()
        val entity = runBlocking { app.db.bookDao().getById(book.id) }!!
        runBlocking {
            app.db.bookDao().update(entity.copy(lastOpenedDate = "2026-11-26"))
        }

        composeRule.activity.setContent {
            NavGraph(openBookUri = null, onBookUriConsumed = {})
        }
        // The hero card carries the distinct "Continue reading" label;
        // waiting on it proves the hero (not just the grid) rendered.
        assertTrue(
            "library did not render with the hero card",
            waitForText(context.getString(R.string.continue_reading))
        )

        val placeholder = context.getString(R.string.search_books)
        assertTrue(
            "search field did not appear",
            waitForText(placeholder)
        )
        composeRule.onNodeWithText(placeholder).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("a")

        // The hero card vanishes on the first keystroke; the field
        // must keep focus (and the keyboard with it) instead of
        // being disposed by the lazy-layout index shift.
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.onNodeWithText("a").assertExists()
    }
}
