package com.novelfa.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.novelfa.reader.data.PrefsStore

/**
 * Root: the Material3 color scheme follows the app theme setting
 * (Settings → dark/light, default dark). This used to be a bare
 * MaterialTheme — which defaults to the light scheme — so the theme switch
 * in Settings had no visible effect anywhere in the app UI.
 */
@Composable
fun NovelfaApp() {
    val context = LocalContext.current
    val prefs = remember { PrefsStore(context) }
    val themeKey by prefs.theme.collectAsState(initial = "dark")
    MaterialTheme(
        colorScheme = if (themeKey == "light") lightColorScheme() else darkColorScheme()
    ) {
        NavGraph()
    }
}
