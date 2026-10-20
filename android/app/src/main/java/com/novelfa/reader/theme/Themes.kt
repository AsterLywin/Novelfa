package com.novelfa.reader.theme

/**
 * Minimal stub per Task 6 brief ruling (binding, from preflight).
 *
 * Ports WPF `MainWindow.ReaderTheme` (MainWindow.xaml.cs:66-96) with the
 * exact dark-theme hex (MainWindow.xaml.cs:101). Task 7 extends this SAME
 * file (5 more themes + character pages + accents) keeping the signature.
 */
data class ReaderTheme(
    val key: String,
    val bg: String,
    val text: String,
    val thumb: String,
    val track: String,
    val divider: String,
    val isDark: Boolean,
    /** Link/accent-foreground color, guaranteed readable on [bg]. Defaults to [thumb]. */
    val link: String = thumb
)

object Themes {
    val DARK = ReaderTheme(
        key = "dark",
        bg = "#1C1A2A",
        text = "#E4E1F0",
        thumb = "#A78BFA",
        track = "#1C1A29",
        divider = "#2A2838",
        isDark = true,
        link = "#A78BFA"
    )

    /**
     * Static reader themes ported from MainWindow.xaml.cs:99-107.
     * Field order (key, bg, text, thumb, track, divider, isDark).
     */
    val WHITE = ReaderTheme(
        key = "white",
        bg = "#FFFFFF",
        text = "#000000",
        thumb = "#C0C0C0",
        track = "#F0F0F0",
        divider = "#EEEEEE",
        isDark = false,
        link = "#1D4ED8"
    )
    val CREAM = ReaderTheme(
        key = "cream",
        bg = "#F4ECD8",
        text = "#3B2F1E",
        thumb = "#C9B48A",
        track = "#EADFC4",
        divider = "#D9CCAA",
        isDark = false,
        link = "#1D4ED8"
    )
    val BLACK = ReaderTheme(
        key = "black",
        bg = "#000000",
        text = "#C8C8C8",
        thumb = "#555555",
        track = "#111111",
        divider = "#222222",
        isDark = true,
        link = "#8AB4F8"
    )
    val GRAY = ReaderTheme(
        key = "gray",
        bg = "#2F3237",
        text = "#E0E0E0",
        thumb = "#7A7F87",
        track = "#26282C",
        divider = "#41454B",
        isDark = true,
        link = "#8AB4F8"
    )
    val GREEN = ReaderTheme(
        key = "green",
        bg = "#DCE8D5",
        text = "#1F2D1F",
        thumb = "#9DB594",
        track = "#CFDDC7",
        divider = "#BCCFB2",
        isDark = false,
        link = "#1D4ED8"
    )

    val ALL: List<ReaderTheme> = listOf(DARK, WHITE, CREAM, BLACK, GRAY, GREEN)

    /** fa display names (keys of the Task 4 Strings map). */
    val NAMES_FA: Map<String, String> = mapOf(
        "dark" to "تیره",
        "white" to "سفید",
        "cream" to "کرمی",
        "black" to "مشکی",
        "gray" to "خاکستری",
        "green" to "سبز ملایم"
    )

    /** en display names, same keys as [NAMES_FA]. */
    val NAMES_EN: Map<String, String> = mapOf(
        "dark" to "Dark",
        "white" to "White",
        "cream" to "Cream",
        "black" to "Black",
        "gray" to "Gray",
        "green" to "Soft Green"
    )

    fun findStatic(key: String): ReaderTheme? = ALL.firstOrNull { it.key == key }
}
