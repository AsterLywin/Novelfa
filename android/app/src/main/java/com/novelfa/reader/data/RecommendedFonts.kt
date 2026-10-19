package com.novelfa.reader.data

import android.content.Context
import java.io.File

/**
 * Bundled "recommended" fonts (WPF RecommendedFontService parity: B Koodak,
 * Vazirmatn — the two fonts the desktop client ships so machines without
 * them installed can still use them).
 *
 * The .ttf/.otf files live in `assets/fonts/` (copied there by the
 * developer; binary-safe, unlike the MCP text-only upload path) and are
 * installed into the FontStore dir on first launch. The existing
 * font-family pipeline (@font-face injection, reader family list) picks
 * them up with no further changes. Install is idempotent — it also
 * repairs a bundled font the user deleted.
 */
object RecommendedFonts {
    private const val ASSET_DIR = "fonts"

    /** Asset file names of the bundled fonts (empty when none shipped). */
    fun assetNames(context: Context): List<String> = runCatching {
        context.assets.list(ASSET_DIR)
            ?.filter {
                it.endsWith(".ttf", ignoreCase = true) ||
                    it.endsWith(".otf", ignoreCase = true)
            }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** True when [fileName] is one of the bundled fonts (not user-added). */
    fun isBundled(context: Context, fileName: String): Boolean =
        assetNames(context).any { it.equals(fileName, ignoreCase = true) }

    /** Copies any missing bundled fonts from assets into the FontStore dir. */
    fun installIfMissing(context: Context) {
        val names = assetNames(context)
        if (names.isEmpty()) return
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        for (name in names) {
            val dest = File(dir, name)
            if (dest.exists()) continue
            runCatching {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

}
