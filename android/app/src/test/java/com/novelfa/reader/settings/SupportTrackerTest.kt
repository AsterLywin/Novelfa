package com.novelfa.reader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8 RED: SupportTracker (port of MainWindow.xaml.cs support reminder:
 * SupportIntervalSeconds = 30*60, accumulate foreground seconds, prompt once
 * at threshold then reset; enabled toggle).
 */
class SupportTrackerTest {

    @Test
    fun accumulate_prompt_reset() {
        val prefs = FakeSupportPrefs()
        val tracker = SupportTracker(prefs)
        assertFalse(tracker.shouldPrompt())
        tracker.addForegroundSeconds(1799)
        assertFalse(tracker.shouldPrompt())
        tracker.addForegroundSeconds(1)
        assertTrue(tracker.shouldPrompt())
        assertEquals(1800, prefs.supportSeconds)
        tracker.onPromptShown()
        assertEquals(0, prefs.supportSeconds)
        assertFalse(tracker.shouldPrompt())
    }

    @Test
    fun disabled_neverPrompts() {
        val prefs = FakeSupportPrefs()
        val tracker = SupportTracker(prefs)
        tracker.setEnabled(false)
        tracker.addForegroundSeconds(3600)
        assertFalse(tracker.shouldPrompt())
        tracker.setEnabled(true)
        assertTrue(tracker.shouldPrompt())
    }

    @Test
    fun resumePause_accumulatesElapsedViaClock() {
        var now = 0L
        val prefs = FakeSupportPrefs()
        val tracker = SupportTracker(prefs) { now }
        tracker.notifyResumed()
        now = 12_000L
        tracker.notifyPaused()
        assertEquals(12, prefs.supportSeconds)
        // Pause without resume adds nothing.
        now = 99_000L
        tracker.notifyPaused()
        assertEquals(12, prefs.supportSeconds)
    }

    @Test
    fun interval_is1800() {
        assertEquals(1800, SupportTracker.SUPPORT_INTERVAL_SECONDS)
    }
}
