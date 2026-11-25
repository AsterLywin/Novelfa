package com.novelfa.reader.reader

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.novelfa.reader.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * PDF search-overlay inset regression test (standing rule,
 * 2026-11-25): the overlay's field row must start BELOW the
 * status bar. The bug (2026-11-25) was the raw Column without
 * any status-bar inset, so its top ~16dp hid under the bar.
 *
 * The row's top must equal (status-bar height + the Column's
 * 8dp padding). The overlay takes its insets as a parameter
 * (default: the real status bars), so the test passes a fake
 * 24dp inset and expects the row at 32dp: with the inset
 * modifier missing it sits at 8dp, and the test fails.
 *
 * Robolectric's window reports a 0 status-bar inset, so the
 * real insets cannot drive this test — injecting a value was
 * tried first (2026-11-25) and proven futile: a dispatched
 * inset is consumed before it reaches the holder, a direct
 * listener call sets the holder to 24px but the composition
 * never re-reads it, and every refresh is wiped by the effect's
 * own 0-inset dispatch. The fake-inset parameter is the fix.
 *
 * Setup mirrors InstantReadNavigationTest: MainActivity (the
 * only activity in the manifest) via the Compose rule, content
 * set on the activity directly (the rule's setContent refuses
 * because MainActivity already sets its own content), looper
 * pumped by hand (idleFor, not idle — Compose frames are
 * Choreographer callbacks scheduled for the next vsync).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = com.novelfa.reader.NovelfaApplication::class)
class PdfSearchOverlayTest {

    // MainActivity (not a bare ComponentActivity): createComposeRule()
    // launches a ComponentActivity that Robolectric cannot resolve —
    // it is in no manifest — while MainActivity IS declared.
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pdfSearchBar_startsBelowStatusBar() {
        // Hoisted out of the composition: creating a
        // MutableInteractionSource inside setContent trips
        // lint (UnrememberedMutableInteractionSource).
        val chromeTapSource = MutableInteractionSource()
        // getBoundsInRoot() returns a DpRect, so the whole
        // comparison stays in Dp — no pixel conversion needed.
        composeRule.activity.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    PdfSearchOverlay(
                        searchOpen = true,
                        searchQuery = "test",
                        onQueryChange = {},
                        searchResult = PdfSearchState(emptyList()),
                        searchRan = false,
                        searchAvailable = true,
                        searchBusy = false,
                        chromeTapSource = chromeTapSource,
                        onRunSearch = {},
                        onClearAndClose = {},
                        onStep = {},
                        // Fake 24dp status bar: Robolectric's real
                        // one is 0 and cannot be injected (see the
                        // class doc). The production default is the
                        // real WindowInsets.statusBars.
                        statusBarInsets = WindowInsets(top = 24.dp)
                    )
                }
            }
        }
        // Let the enter animation (slideInVertically + fadeIn)
        // finish, then measure the settled position.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        val probeNode = composeRule.onNodeWithTag("pdfSearchBar", useUnmergedTree = true).fetchSemanticsNode()
        println("OVERLAY-PROBE boundsInRoot=${probeNode.boundsInRoot}")
        composeRule.onNodeWithTag("pdfSearchBar", useUnmergedTree = true).assertIsDisplayed()
        val expectedTop = 24.dp + 8.dp
        val barTop = composeRule.onNodeWithTag("pdfSearchBar", useUnmergedTree = true).getBoundsInRoot().top
        assertTrue(
            "search bar top $barTop, expected $expectedTop " +
                "(fake status bar 24dp + 8dp padding)",
            abs(barTop.value - expectedTop.value) <= 1f
        )
    }
}
