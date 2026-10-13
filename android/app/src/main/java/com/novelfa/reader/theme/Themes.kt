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
    val isDark: Boolean
)

object Themes {
    val DARK = ReaderTheme(
        key = "dark",
        bg = "#1C1A2A",
        text = "#E4E1F0",
        thumb = "#A78BFA",
        track = "#1C1A29",
        divider = "#2A2838",
        isDark = true
    )
}
