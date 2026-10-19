package com.novelfa.reader.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/**
 * App-wide UI font: Vazirmatn (regular 400 + bold 700).
 *
 * Loaded from the already-bundled `assets/fonts/Vazirmatn-Regular.ttf` /
 * `assets/fonts/Vazirmatn-Bold.ttf` (the same files the reader font picker
 * uses) via Typeface.createFromAsset — so no `res/font/` copies and no
 * binary uploads are needed, and the ~250KB isn't duplicated in the APK.
 *
 * The family is built once and cached in a static var, using the
 * application context (never an Activity, so nothing leaks). If the asset
 * is missing for any reason, it falls back to the platform default instead
 * of crashing the app on launch.
 *
 * This only styles the Compose UI (menus, dialogs, top bars). Book content
 * keeps its own reader font pipeline (HTML @font-face, per-book setting).
 */
private val lock = Any()

@Volatile
private var cachedFamily: FontFamily? = null

fun vazirmatnUiFontFamily(appContext: Context): FontFamily {
    cachedFamily?.let { return it }
    return synchronized(lock) {
        cachedFamily ?: buildFamily(appContext).also { cachedFamily = it }
    }
}

private fun buildFamily(appContext: Context): FontFamily = try {
    val regular = Typeface.createFromAsset(appContext.assets, "fonts/Vazirmatn-Regular.ttf")
    val bold = Typeface.createFromAsset(appContext.assets, "fonts/Vazirmatn-Bold.ttf")
    FontFamily(
        ComposeTypeface(regular, FontWeight.Normal),
        ComposeTypeface(bold, FontWeight.Bold)
    )
} catch (_: Exception) {
    FontFamily.Default
}

/**
 * App typography: every Material3 text style defaults to the Vazirmatn UI
 * font instead of the platform default.
 */
@Composable
fun rememberAppTypography(): Typography {
    val appContext = LocalContext.current.applicationContext
    return remember { Typography(defaultFontFamily = vazirmatnUiFontFamily(appContext)) }
}
