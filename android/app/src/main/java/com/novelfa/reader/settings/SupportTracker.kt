package com.novelfa.reader.settings

import com.novelfa.reader.data.PrefsStore
import kotlinx.coroutines.runBlocking

/**
 * Foreground-seconds store. Production bridges Task 3 PrefsStore (DataStore)
 * via [PrefsStoreSupportPrefs]; tests use [InMemorySupportPrefs].
 */
interface SupportPrefs {
    var supportSeconds: Int
    var supportEnabled: Boolean
}

/** In-memory SupportPrefs for tests and previews. */
class InMemorySupportPrefs(
    override var supportSeconds: Int = 0,
    override var supportEnabled: Boolean = true
) : SupportPrefs

/** Production SupportPrefs backed by Task 3 PrefsStore (DataStore). */
class PrefsStoreSupportPrefs(private val store: PrefsStore) : SupportPrefs {
    override var supportSeconds: Int
        get() = runBlocking { store.getInt(PrefsStore.SUPPORT_SECONDS, 0) }
        set(value) = runBlocking { store.setInt(PrefsStore.SUPPORT_SECONDS, value) }
    override var supportEnabled: Boolean
        get() = runBlocking { store.supportEnabledNow() }
        set(value) = runBlocking { store.setSupportEnabled(value) }
}

/**
 * Support-reminder tracker ported from MainWindow.xaml.cs
 * (SupportIntervalSeconds = 30*60, 10s tick while a book is open and the
 * window is active, prompt once at threshold then reset to 0 and persist).
 *
 * Mobile replaces the DispatcherTimer with explicit foreground signals:
 * call [notifyResumed]/[notifyPaused] from lifecycle callbacks, or
 * [addForegroundSeconds] directly from a timer loop.
 */
class SupportTracker(
    private val prefs: SupportPrefs,
    private val timeSource: () -> Long = { System.currentTimeMillis() }
) {

    private var resumeMs: Long? = null

    fun notifyResumed() {
        resumeMs = timeSource()
    }

    fun notifyPaused() {
        val resumed = resumeMs
        resumeMs = null
        if (resumed != null) {
            val elapsed = ((timeSource() - resumed) / 1000).toInt().coerceAtLeast(0)
            addForegroundSeconds(elapsed)
        }
    }

    fun addForegroundSeconds(n: Int) {
        if (n <= 0) return
        prefs.supportSeconds = prefs.supportSeconds + n
    }

    fun shouldPrompt(): Boolean =
        prefs.supportEnabled && prefs.supportSeconds >= SUPPORT_INTERVAL_SECONDS

    fun onPromptShown() {
        prefs.supportSeconds = 0
    }

    fun setEnabled(enabled: Boolean) {
        prefs.supportEnabled = enabled
    }

    companion object {
        /** Same as WPF MainWindow.SupportIntervalSeconds = 30 * 60. */
        const val SUPPORT_INTERVAL_SECONDS = 30 * 60
    }
}
