package com.novelfa.reader.settings

import com.novelfa.reader.data.PrefsStore

/**
 * Foreground-seconds store. Production bridges Task 3 PrefsStore (DataStore)
 * via [PrefsStoreSupportPrefs]; tests use [InMemorySupportPrefs].
 * All access is suspend: never blocks the main thread.
 */
interface SupportPrefs {
    suspend fun getSeconds(): Int
    suspend fun setSeconds(value: Int)
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(value: Boolean)
}

/** In-memory SupportPrefs for tests and previews. */
class InMemorySupportPrefs(
    private var seconds: Int = 0,
    private var enabled: Boolean = true
) : SupportPrefs {
    override suspend fun getSeconds(): Int = seconds
    override suspend fun setSeconds(value: Int) {
        seconds = value
    }
    override suspend fun isEnabled(): Boolean = enabled
    override suspend fun setEnabled(value: Boolean) {
        enabled = value
    }
}

/** Production SupportPrefs backed by Task 3 PrefsStore (DataStore). */
class PrefsStoreSupportPrefs(private val store: PrefsStore) : SupportPrefs {
    override suspend fun getSeconds(): Int = store.getInt(PrefsStore.SUPPORT_SECONDS, 0)
    override suspend fun setSeconds(value: Int) = store.setInt(PrefsStore.SUPPORT_SECONDS, value)
    override suspend fun isEnabled(): Boolean = store.supportEnabledNow()
    override suspend fun setEnabled(value: Boolean) = store.setSupportEnabled(value)
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

    suspend fun notifyPaused() {
        val resumed = resumeMs
        resumeMs = null
        if (resumed != null) {
            val elapsed = ((timeSource() - resumed) / 1000).toInt().coerceAtLeast(0)
            addForegroundSeconds(elapsed)
        }
    }

    suspend fun addForegroundSeconds(n: Int) {
        if (n <= 0) return
        prefs.setSeconds(prefs.getSeconds() + n)
    }

    suspend fun shouldPrompt(): Boolean =
        prefs.isEnabled() && prefs.getSeconds() >= SUPPORT_INTERVAL_SECONDS

    suspend fun onPromptShown() {
        prefs.setSeconds(0)
    }

    suspend fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
    }

    companion object {
        /** Same as WPF MainWindow.SupportIntervalSeconds = 30 * 60. */
        const val SUPPORT_INTERVAL_SECONDS = 30 * 60
    }
}
