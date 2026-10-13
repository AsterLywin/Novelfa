package com.novelfa.reader.theme

/**
 * Resolve a stored theme key to a ReaderTheme — ported from
 * MainWindow FindTheme/GetCurrentTheme (MainWindow.xaml.cs:123-140).
 *
 * Static key -> static theme; rz.* key -> character page if unlocked,
 * else DARK fallback; unknown key -> DARK fallback.
 */
fun currentReaderTheme(
    themeKey: String,
    unlocked: (CharacterTheme) -> Boolean
): ReaderTheme {
    Themes.findStatic(themeKey)?.let { return it }
    if (themeKey.startsWith("rz.")) {
        val page = characterPages().firstOrNull { it.key == themeKey }
        if (page != null) {
            val id = themeKey.removePrefix("rz.").substringBefore('.')
            val theme = CharacterThemes.find(id)
            if (theme != null && unlocked(theme)) return page
        }
    }
    return Themes.DARK
}
