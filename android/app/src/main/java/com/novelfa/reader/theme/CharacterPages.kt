package com.novelfa.reader.theme

/**
 * Reading-page theme for each character (night + day), built from the
 * character's own palette — ported from BuildCharacterPages
 * (MainWindow.xaml.cs:112-121).
 *
 * EXACT mapping:
 * night = (key, dark.card, dark.text, dark.accent, dark.sidebar, dark.border, isDark=true)
 * day   = (key, light.hero1, light.text, light.accent, light.hover, light.border, isDark=false)
 */
fun characterPages(): List<ReaderTheme> {
    val list = ArrayList<ReaderTheme>(CharacterThemes.All.size * 2)
    for (c in CharacterThemes.All) {
        list.add(
            ReaderTheme(
                key = c.pageKeyNight,
                bg = c.dark.card,
                text = c.dark.text,
                thumb = c.dark.accent,
                track = c.dark.sidebar,
                divider = c.dark.border,
                isDark = true
            )
        )
        list.add(
            ReaderTheme(
                key = c.pageKeyDay,
                bg = c.light.hero1,
                text = c.light.text,
                thumb = c.light.accent,
                track = c.light.hover,
                divider = c.light.border,
                isDark = false
            )
        )
    }
    return list
}
