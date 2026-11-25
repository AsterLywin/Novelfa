package com.novelfa.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.RoomBookRepository
import com.novelfa.reader.library.LibraryViewModel
import com.novelfa.reader.settings.PinManager
import com.novelfa.reader.settings.PrefsStorePinPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Direct openExternal test (no Compose): the REAL LibraryViewModel +
 * the REAL Importer + the REAL Room database under Robolectric open a
 * real .md file in place. If this fails, the navigation test above it
 * cannot pass either — this is the diagnostic split between "the
 * import path is broken under Robolectric" and "the Compose /
 * navigation side is broken".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NovelfaApplication::class)
class OpenExternalTest {

    @Test
    fun openExternal_mdFile_opens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as NovelfaApplication
        val prefs = PrefsStore(context.applicationContext)
        val vm = LibraryViewModel(
            RoomBookRepository(app.db.bookDao()),
            Importer(context, app.db, prefs),
            PinManager(PrefsStorePinPrefs(prefs)),
            cacheDir = context.cacheDir,
            filesDir = context.filesDir,
            prefs = prefs
        )
        val file = File(context.filesDir, "diag.md").apply {
            writeText("# Test Book\n\nSome body text here.\n", Charsets.UTF_8)
        }
        val result = runBlocking { vm.openExternal(Uri.fromFile(file), null) }
        when (result) {
            is LibraryViewModel.OpenExternalResult.Opened ->
                assertTrue(result.bookId.isNotBlank())
            is LibraryViewModel.OpenExternalResult.Failed ->
                fail("openExternal failed: ${result.reason} / ${result.detail}")
        }
    }
}
