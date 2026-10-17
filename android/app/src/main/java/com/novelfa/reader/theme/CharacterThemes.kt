package com.novelfa.reader.theme

/**
 * Palette of a character theme (dark or light variant), ported from
 * EpubReader/Services/CharacterThemes.cs ThemePalette.
 *
 * Parse order (space-separated, 16 values):
 * bg, sidebar, card, text, muted, hover, border, soft, hero1, hero2,
 * accent, accentDark, grad1, grad2, aura1, aura2.
 *
 * RULING (binding, Task 7 brief): NO WPF Geometry emblems on mobile —
 * the avatar is an initial letter rendered with palette colors.
 * NO particle/glyph canvas in v1 (deferred).
 */
data class ThemePalette(
    val bg: String,
    val sidebar: String,
    val card: String,
    val text: String,
    val muted: String,
    val hover: String,
    val border: String,
    val soft: String,
    val hero1: String,
    val hero2: String,
    val accent: String,
    val accentDark: String,
    val grad1: String,
    val grad2: String,
    val aura1: String,
    val aura2: String
) {
    companion object {
        fun parse(spaced: String): ThemePalette {
            val v = spaced.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
            require(v.size >= 16) { "palette needs 16 colors" }
            return ThemePalette(
                bg = v[0], sidebar = v[1], card = v[2], text = v[3],
                muted = v[4], hover = v[5], border = v[6], soft = v[7],
                hero1 = v[8], hero2 = v[9], accent = v[10], accentDark = v[11],
                grad1 = v[12], grad2 = v[13], aura1 = v[14], aura2 = v[15]
            )
        }
    }
}

/**
 * One character theme. RequiredRank is the RankService rank index that
 * unlocks it; >= 99 means secret-only (never unlocked by rank, hidden
 * from the list until unlocked via secret).
 */
data class CharacterTheme(
    val id: String,
    val nameFa: String,
    val taglineFa: String,
    val particle: String,
    val requiredRank: Int,
    val dark: ThemePalette,
    val light: ThemePalette
) {
    val secretOnly: Boolean get() = requiredRank >= 99

    /** Reading-page theme key (night / day) stored in Book.Theme. */
    val pageKeyNight: String get() = "rz.$id.night"
    val pageKeyDay: String get() = "rz.$id.day"

    fun palette(light: Boolean): ThemePalette = if (light) this.light else this.dark
}

object CharacterThemes {
    // Rank order: subaru (open from the start) to emilia (top rank).
    // Palette strings + fa texts copied VERBATIM from CharacterThemes.cs:129-170.
    // Verified 2026-10-13 by diffing each Parse("...") literal and each fa
    // literal against the C# source (see task-7-report.md for the method).
    val All: List<CharacterTheme> = listOf(
        CharacterTheme(
            "subaru", "سوبارو",
            "سبزِ شب و نارنجیِ گرم؛ هر بار از اول، با اراده‌ای نو",
            "embers", 0,
            ThemePalette.parse("#0C1210 #111A16 #17231D #E8F0EA #8AA095 #1F2F27 #26382F #3A2616 #1E2F25 #101A14 #FF8A3D #F26B1D #FFB066 #F26B1D #FF8A3D #2FBF71"),
            ThemePalette.parse("#F3F8F4 #FFFFFF #FFFFFF #14231B #5E7A6B #E9F3EC #D5E5DA #FFEBDB #EAF6EE #FFE7D1 #CC4A08 #A83806 #FF9A52 #E8590C #FF8A3D #2FBF71")
        ),
        CharacterTheme(
            "ram", "رام",
            "صورتیِ تند، زبانِ تیز و بادی که نمی‌ایسته",
            "petals", 1,
            ThemePalette.parse("#130A10 #1A0E16 #25121F #FBE9F1 #B58AA2 #341A2B #43203A #4A1735 #3A1530 #1B0C16 #FF6FAE #E8458F #FF9CC8 #E11D6B #FF4D94 #C81E4F"),
            ThemePalette.parse("#FFF3F8 #FFFFFF #FFFFFF #3A1226 #9A6580 #FFE6F0 #F8D2E3 #FFE0EE #FFEAF3 #FFD3E6 #D6246E #B01457 #FF7FB4 #D6246E #FF6FAE #E11D48")
        ),
        CharacterTheme(
            "rem", "رم",
            "آبیِ آرومِ آسمون و دلی که بی‌دریغ می‌ده",
            "sparkles", 2,
            ThemePalette.parse("#080E1A #0C1424 #111C32 #E6F1FF #85A0C4 #182A4A #1F3358 #14335E #153059 #0A1428 #5BB5FF #2F8FEA #9AD3FF #2F7FE0 #4DA8FF #22D3EE"),
            ThemePalette.parse("#EFF6FF #FFFFFF #FFFFFF #0F2347 #5C7AA6 #E1EFFF #CFE2F8 #DCEEFF #E6F2FF #CDE5FF #1D74D8 #1558AD #6DB8FF #1D74D8 #5BB5FF #22D3EE")
        ),
        CharacterTheme(
            "beatrice", "بیاتریس",
            "نگهبانِ کتابخانه‌ی ممنوعه؛ طلاییِ شمع و پروانه‌های شب",
            "butterflies", 3,
            ThemePalette.parse("#100E1C #161228 #1E1933 #F6EEDC #A99FBD #2A2347 #342C57 #3A2E4A #2E2350 #15112A #F2C14E #E0A526 #FFE08A #E8A33A #F2C14E #F08DB6"),
            ThemePalette.parse("#FFF8EA #FFFDF6 #FFFFFF #2C2140 #8C7A66 #FFF0D2 #F1DFB8 #FFEFC7 #FFF3D6 #FBD9E6 #A8690A #86500A #F5C75A #D9902B #F2C14E #F08DB6")
        ),
        CharacterTheme(
            "emilia", "امیلیا",
            "نقره‌ای و یخی؛ آرامشِ برف و بنفشِ گل‌های بهاری",
            "snow", 4,
            ThemePalette.parse("#0D0F1E #12152A #191D38 #EEF1FF #96A0C8 #232850 #2B3263 #2A2A5C #28306A #101431 #B7A8FF #8F7CF0 #D8CFFF #7C6CF0 #A99BFF #8FE0FF"),
            ThemePalette.parse("#F4F5FF #FFFFFF #FFFFFF #1B1E3F #6C73A6 #E9EBFF #D9DCF6 #E6E3FF #EEF0FF #DDE3FF #5F4BD6 #4A38B8 #A99BFF #6D5BE0 #A99BFF #7FD6F5")
        ),
        CharacterTheme(
            "echidna", "اکیدنا",
            "سبزِ زهرآگین و چایِ نیمه‌شب؛ طمعِ سیری‌ناپذیر برای دانستن",
            "leaves", 5,
            ThemePalette.parse("#040A07 #08120D #0E1D15 #E6FFEF #7FB095 #14301F #1B4A2E #0F2A1B #0F3A22 #06120B #2CFF8A #12C968 #7CFFB5 #0FA85A #2CFF8A #00E5C0"),
            ThemePalette.parse("#F0FAF4 #FFFFFF #FFFFFF #0B2417 #5B8068 #E2F5EA #C6E6D2 #D8F5E3 #D2F4E0 #BDEBD2 #0B8F4A #076B37 #3DDB86 #0B8F4A #1FD174 #00BFA0")
        ),
        // Upstream f352105: «سوبارو · شکم‌پرستی» (rank 6, ash particles).
        // Palettes copied verbatim from CharacterThemes.cs.
        CharacterTheme(
            "tsugi", "سوبارو · شکم‌پرستی",
            "خاکستر، کتاب مردگان و خاطره‌ای که تکه‌تکه دوخته می‌شه",
            "ash", 6,
            ThemePalette.parse("#141415 #19191A #1E1E20 #F1EBE6 #9A8C80 #262325 #3B3432 #2A2422 #28262B #151416 #E3B46E #C8934A #EBC585 #D39A4E #A39286 #7C6068"),
            ThemePalette.parse("#F5F0EE #FBF8F6 #FDFAF8 #2E2429 #7C6368 #EDE3E0 #E2C9C0 #F3E4DC #EBD9D3 #D9C2C0 #A9732B #85561A #E3B46E #C28A3E #A78A89 #C0392F")
        )
    )

    fun find(id: String?): CharacterTheme? {
        if (id.isNullOrEmpty()) return null
        return All.firstOrNull { it.id == id }
    }

    /**
     * Count shown in the counter: locked secret-only themes are excluded,
     * like CharacterThemes.VisibleCount.
     */
    fun visibleCount(isUnlocked: (CharacterTheme) -> Boolean): Int =
        All.count { !it.secretOnly || isUnlocked(it) }
}
