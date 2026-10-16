package com.novelfa.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35: Android 15 enforces edge-to-edge; Scaffolds apply the
        // WindowInsets padding so content stays clear of system bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NovelfaApp() }
    }
}
