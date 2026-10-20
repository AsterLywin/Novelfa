package com.novelfa.reader.theme

/**
 * Seven ready-made app accent colors, ported from ThemeManager.cs:28-40.
 */
data class AccentPreset(val nameFa: String, val nameEn: String, val hex: String) {
    fun displayName(isEnglish: Boolean): String = if (isEnglish) nameEn else nameFa
}

object Accent {
    const val DEFAULT = "#7C3AED"

    val PRESETS: List<AccentPreset> = listOf(
        AccentPreset("بنفش", "Purple", "#7C3AED"),
        AccentPreset("آبی", "Blue", "#2563EB"),
        AccentPreset("سبز", "Green", "#16A34A"),
        AccentPreset("زرد", "Yellow", "#EAB308"),
        AccentPreset("نارنجی", "Orange", "#EA580C"),
        AccentPreset("قرمز", "Red", "#DC2626"),
        AccentPreset("صورتی", "Pink", "#DB2777")
    )
}
