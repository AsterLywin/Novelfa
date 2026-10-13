package com.novelfa.reader.theme

import com.novelfa.reader.data.FontStore
import java.io.File

/**
 * Bundled Vazirmatn files copied to assets/fonts (see task-7-report.md
 * for SHAs/sizes). Null entries mean the asset is missing.
 */
data class VazirmatnFiles(val regular: File?, val bold: File?)

/**
 * Resolve the font files for [family], improving the Task 6 filename
 * heuristic. Order (first non-empty wins):
 * 1) stored fonts whose stored name (file name without extension)
 *    equals [family] (case-insensitive),
 * 2) bundled Vazirmatn assets when [family] matches Vazirmatn
 *    ("vazir" contained, case-insensitive),
 * 3) filename-contains fallback over stored fonts (case-insensitive).
 *
 * No GDI name-table parsing on mobile — deferred (see report).
 */
fun resolveFontFiles(
    family: String,
    stored: List<File>,
    assets: VazirmatnFiles?
): List<File> {
    if (family.isBlank()) return emptyList()
    val exact = stored.filter {
        it.nameWithoutExtension.equals(family, ignoreCase = true)
    }
    if (exact.isNotEmpty()) return exact
    if (family.contains("vazir", ignoreCase = true) && assets != null) {
        val bundled = listOfNotNull(assets.regular, assets.bold).filter { it.exists() }
        if (bundled.isNotEmpty()) return bundled
    }
    return stored.filter { it.name.contains(family, ignoreCase = true) }
}

/** Brief-signature overload reading stored fonts from [FontStore]. */
fun resolveFontFiles(
    family: String,
    store: FontStore,
    assets: VazirmatnFiles?
): List<File> = resolveFontFiles(family, store.list(), assets)
