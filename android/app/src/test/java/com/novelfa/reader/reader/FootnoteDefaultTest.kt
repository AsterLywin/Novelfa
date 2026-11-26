package com.novelfa.reader.reader

import androidx.test.core.app.ApplicationProvider
import com.novelfa.reader.data.PrefsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-11-26 bug round: "popup on the page" no longer works. The
 * footnote chain is intact — the break is the setting: the default
 * is "navigate" (Windows shows the popup), and the spacing dialog's
 * reset silently turns the popup off. This test pins the default.
 * EXPECTED TO FAIL until the default becomes "popup".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FootnoteDefaultTest {

    @Test
    fun freshStore_defaultsFootnoteDisplayToPopup() {
        val prefs = PrefsStore(
            ApplicationProvider.getApplicationContext()
        )
        val value = runBlocking { prefs.getFootnoteDisplay() }
        assertEquals("popup", value)
    }
}
