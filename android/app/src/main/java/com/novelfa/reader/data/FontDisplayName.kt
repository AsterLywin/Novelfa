package com.novelfa.reader.data

/**
 * Display name for a font file: bundled fonts get proper names in BOTH
 * UI languages (fa/en data pair, like the accent/rank/theme name pairs —
 * never a single-language hardcoded label); everything else shows the
 * file name without its extension.
 *
 * Lives in data (not theme) because BackupService writes it into the
 * backup manifest: keeping it in a UI file made data depend on theme.
 */
fun fontDisplayName(fileName: String, isEnglish: Boolean = false): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    return when (base.lowercase()) {
        "vazirmatn-regular.ttf" -> if (isEnglish) "Vazirmatn Regular" else "وزیر متن عادی"
        "vazirmatn-bold.ttf" -> if (isEnglish) "Vazirmatn Bold" else "وزیر متن بولد"
        "b koodak bold_0.ttf" -> if (isEnglish) "B Koodak Bold" else "کودک بولد"
        else -> base.substringBeforeLast('.')
    }
}
