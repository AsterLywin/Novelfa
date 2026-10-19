package com.novelfa.reader.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.novelfa.reader.R

/**
 * App-wide UI font: Vazirmatn (regular 400 + bold 700).
 *
 * The TTFs ship in `src/main/assets/fonts/`; the `copyUiFonts` Gradle task
 * (app/build.gradle.kts) copies + renames them into a generated `res/font/`
 * dir at build time, so `R.font.vazirmatn_regular` / `R.font.vazirmatn_bold`
 * resolve like normal resources without checking binaries into git.
 *
 * This only styles the Compose UI (menus, dialogs, top bars). Book content
 * keeps its own reader font pipeline (HTML @font-face, per-book setting).
 */
private val VazirmatnUi = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

/**
 * Material3 Typography with every text style set to the Vazirmatn UI font.
 * (This Material3 version has no `defaultFontFamily` parameter, so every
 * style is copied explicitly.)
 */
@Composable
fun rememberAppTypography(): Typography {
    return remember {
        val base = Typography()
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = VazirmatnUi),
            displayMedium = base.displayMedium.copy(fontFamily = VazirmatnUi),
            displaySmall = base.displaySmall.copy(fontFamily = VazirmatnUi),
            headlineLarge = base.headlineLarge.copy(fontFamily = VazirmatnUi),
            headlineMedium = base.headlineMedium.copy(fontFamily = VazirmatnUi),
            headlineSmall = base.headlineSmall.copy(fontFamily = VazirmatnUi),
            titleLarge = base.titleLarge.copy(fontFamily = VazirmatnUi),
            titleMedium = base.titleMedium.copy(fontFamily = VazirmatnUi),
            titleSmall = base.titleSmall.copy(fontFamily = VazirmatnUi),
            bodyLarge = base.bodyLarge.copy(fontFamily = VazirmatnUi),
            bodyMedium = base.bodyMedium.copy(fontFamily = VazirmatnUi),
            bodySmall = base.bodySmall.copy(fontFamily = VazirmatnUi),
            labelLarge = base.labelLarge.copy(fontFamily = VazirmatnUi),
            labelMedium = base.labelMedium.copy(fontFamily = VazirmatnUi),
            labelSmall = base.labelSmall.copy(fontFamily = VazirmatnUi)
        )
    }
}
