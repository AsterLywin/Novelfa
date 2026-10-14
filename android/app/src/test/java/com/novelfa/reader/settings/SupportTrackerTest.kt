package com.novelfa.reader.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8 RED: SupportTracker (port of MainWindow.xaml.cs support reminder:
 * SupportIntervalSeconds = 30*60, accumulate foreground seconds, prompt once
 * at threshold then reset; enabled toggle). Tracker is suspend (DataStore
 * never blocks main); tests drive it with runBlocking.
 */
class SupportTrackerTest {

    @Test
    fun accumulate_prompt_reset() = runBlocking {
        val prefs = FakeSupportPrefs()
        val tracker = SupportTracker(prefs)
        assertFalse(tracker.shouldPrompt())
        tracker.addForegroundSeconds(1799)
        assertFalse(tracker.shouldPrompt())
        tracker.addForegroundSeconds(1)
        assertTrue(tracker.shouldPrompt())
        assertEquals(1800, prefs.currentSeconds())
        tracker.onPromptShown()
        assertEquals(0, prefs.currentSeconds())
        assertFalse(tracker.shouldPrompt())
    }

    @Test
    fun disabled_neverPrompts() = runBlocking {
        val prefs = FakeSupportPrefs()
        val tracker = SupportTracker(prefs)
        tracker.setEnabled(false)
        tracker.addForegroundSeconds(3600)
        assertFalse(tracker.shouldPrompt())
        tracker.setEnabled(true)
        assertTrue(tracker.shouldPrompt())
    }

    @Test
    fun resumePause_accumulatesElapsedViaClock() = runBlocking {
        var now = 0L
        val prefs = FakeSupportPrefs()
        val tracker = SupportTracker(prefs) { now }
        tracker.notifyResumed()
        now = 12_000L
        tracker.notifyPaused()
        assertEquals(12, prefs.currentSeconds())
        // Pause without resume adds nothing.
        now = 99_000L
        tracker.notifyPaused()
        assertEquals(12, prefs.currentSeconds())
    }

    @Test
    fun interval_is1800() {
        assertEquals(1800, SupportTracker.SUPPORT_INTERVAL_SECONDS)
    }
}
