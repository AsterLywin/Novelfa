package com.novelfa.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Audit finding 4 (standalone): ACTION_SEND_MULTIPLE is ignored.
 *
 * MainActivity.extractBookUri handles only ACTION_VIEW and
 * ACTION_SEND; a share of 2+ files falls to else -> null, so both
 * the cold path (onCreate) and the warm path (onNewIntent ->
 * viewUri) drop the share silently.
 *
 * This probe lives in its own class WITHOUT the Compose rule:
 * the probe itself is a pure function call (extractBookUri is
 * pure in the Intent), and the ImportNavigationAuditTest class's
 * rule launches the real app content whose ActivityScenario
 * teardown deadlocks under Robolectric (waitForActivityToBecomeAnyOf
 * in ActivityScenario.close) — that hang killed three full runs
 * of that class. The other four findings of that class were
 * already confirmed by execution in earlier runs.
 */
@RunWith(RobolectricTestRunner::class)
class ImportSendMultipleAuditTest {

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
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(Uri.fromFile(first), Uri.fromFile(second))
            )
            type = "*/*"
        }
        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBookUri", Intent::class.java
        ).apply { isAccessible = true }
        val result = method.invoke(MainActivity(), intent) as Uri?
        assertEquals(
            "sharing two files must open the first one (extractBookUri returned null)",
            Uri.fromFile(first), result
        )
    }
}
