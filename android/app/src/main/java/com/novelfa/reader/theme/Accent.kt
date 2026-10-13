package com.novelfa.reader.theme

/**
 * Seven ready-made app accent colors, ported from ThemeManager.cs:28-40.
 */
data class AccentPreset(val nameFa: String, val hex: String)

object Accent {
    const val DEFAULT = "#7C3AED"

    val PRESETS: List<AccentPreset> = listOf(
        AccentPreset("بنفش", "#7C3AED"),
        AccentPreset("آبی", "#2563EB"),
        AccentPreset("سبز", "#16A34A"),
        AccentPreset("زرد", "#EAB308"),
        AccentPreset("نارنجی", "#EA580C"),
        AccentPreset("قرمز", "#DC2626"),
        AccentPreset("صورتی", "#DB2777")
    )
}
