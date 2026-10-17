package com.novelfa.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
        setContent {
            NovelfaApp(
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
