package com.novelfa.reader

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.buildPlainPalette
import com.novelfa.reader.theme.parseHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    /**
     * EPUB opened from a file manager (ACTION_VIEW, WPF file-association
     * parity). singleTop + onNewIntent so tapping a file while the app is
     * open reuses this activity instead of stacking a new one.
     */
    private var viewUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35: Android 15 enforces edge-to-edge; Scaffolds apply the
        // WindowInsets padding so content stays clear of system bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewUri = extractEpubUri(intent)
        // Cold-start flash fix: read the appearance prefs ONCE, before the
        // first frame, and seed the UI with them. Without this the first
        // frames render with the collectAsState defaults (dark base,
        // default purple accent, no character theme, Persian) and then
        // visibly snap to the user's real theme when DataStore emits.
        // The preferences file is tiny; the read costs a few milliseconds
        // under the system launch window, far less than a wrong-theme
        // flash. A failed read falls back to the old defaults.
        val appearance = runBlocking(Dispatchers.IO) {
            runCatching { PrefsStore(applicationContext).appearanceSnapshot() }
                .getOrNull()
        }
        // Paint the window itself with the theme's background color so
        // even the pre-first-frame window matches the app.
        appearance?.let { snap ->
            val isLight = snap.theme == "light"
            val bg = snap.charTheme.takeIf { it.isNotBlank() }
                ?.let { CharacterThemes.find(it) }
                ?.let { parseHex(it.palette(isLight).bg) }
                ?: buildPlainPalette(isLight, snap.accent).bg
            window.setBackgroundDrawable(ColorDrawable(bg.toArgb()))
        }
        setContent {
            NovelfaApp(
                appearance = appearance,
                openEpubUri = viewUri,
                onEpubUriConsumed = { viewUri = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractEpubUri(intent)?.let { viewUri = it }
    }

    private fun extractEpubUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.data
    }
}
