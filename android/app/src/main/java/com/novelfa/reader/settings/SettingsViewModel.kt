package com.novelfa.reader.settings

import android.content.Context
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.theme.CharacterTheme
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.RankPrefs
import com.novelfa.reader.theme.RankService
import com.novelfa.reader.theme.ThemeUnlockInfo
import com.novelfa.reader.theme.XpLevel
import com.novelfa.reader.theme.XpRank
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Full XP progress for the settings Progress card (replaces the old
 * finished-books-only rank: the home header already showed the XP level,
 * so settings showed a different rank than home for the same user). */
data class XpProgressSummary(
    val totalXp: Int,
    val level: XpLevel,
    val finishedBooks: Int,
    val legacyRankName: String,
    val legacyIndex: Int,
    /** Nearest still-locked theme by its XP gate, or null when all are open. */
    val nextLockedTheme: CharacterTheme?
)

/**
 * Settings backing model (plain class, like LibraryViewModel — no
 * lifecycle ViewModel dependency). Owns theme/accent/character/font/locale/
 * support/PIN/rank state over [PrefsStore], [PinManager], [BookDao] and
 * [SupportTracker].
 */
class SettingsViewModel(
    val prefs: PrefsStore,
    val pinManager: PinManager,
    val dao: BookDao,
    val rankPrefs: RankPrefs,
    val support: SupportTracker
) {
    val theme: Flow<String> = prefs.theme
    val accent: Flow<String> = prefs.accent
    val charTheme: Flow<String> = prefs.charTheme
    val fontSize: Flow<Int> = prefs.fontSize
    val fontFamily: Flow<String> = prefs.fontFamily
    val readerLineHeight: Flow<String> = prefs.readerLineHeight
    val readerMargin: Flow<String> = prefs.readerMargin
    val readerSpacing: Flow<String> = prefs.readerSpacing
    val lang: Flow<String> = prefs.lang
    val supportEnabled: Flow<Boolean> = prefs.supportEnabled
    val themeParticles: Flow<Boolean> = prefs.themeParticles

    fun rankService(): RankService = RankService(rankPrefs)

    suspend fun setTheme(value: String) = prefs.setTheme(value)
    suspend fun setAccent(value: String) = prefs.setAccent(value)
    suspend fun setCharTheme(value: String?) = prefs.setCharTheme(value ?: "")
    suspend fun setFontSize(value: Int) = prefs.setFontSize(value.coerceIn(10, 72))
    /**
     * Stores a custom font-family name. Returns false (and stores nothing)
     * when the name is blank or contains path separators / markup: the value
     * flows into a CSS font-family and matched file names, so it needs the
     * same sanitization as the font-import dialog.
     */
    suspend fun setFontFamily(value: String): Boolean {
        val clean = value.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\') ||
            clean.contains("..") || clean.contains('<') || clean.contains('>')
        ) {
            return false
        }
        prefs.setFontFamily(clean)
        return true
    }

    /** Default (no font selected): clears the family back to blank. */
    suspend fun clearFontFamily() {
        prefs.setFontFamily("")
    }
    suspend fun setReaderLineHeight(value: String) = prefs.setReaderLineHeight(value)
    suspend fun setReaderMargin(value: String) = prefs.setReaderMargin(value)
    suspend fun setReaderSpacing(value: String) = prefs.setReaderSpacing(value)
    suspend fun setSupportEnabled(value: Boolean) {
        prefs.setSupportEnabled(value)
        support.setEnabled(value)
    }

    suspend fun setThemeParticles(value: Boolean) = prefs.setThemeParticles(value)

    /**
     * Minimal port of Loc.SetLanguage: persist "Lang" ("fa"/"en") and apply
     * the locale to [context] resources. Callers recreate the Activity so the
     * new locale takes effect. (AppCompatDelegate.setApplicationLocales is
     * intentionally NOT used — appcompat is not a dependency; this is the
     * dep-free equivalent.)
     */
    suspend fun setLocale(context: Context, tag: String) {
        val clean = if (tag.startsWith("en")) "en" else "fa"
        prefs.setLang(clean)
        applyLocale(context, clean)
    }

    /**
     * XP level + finished-book count + legacy rank, after the one-time XP
     * migration (idempotent: later calls just read the migrated totals).
     * The effective finished count includes the backup-restored floor, so
     * the display matches the restored record even on an empty library
     * (plan §7).
     */
    suspend fun xpProgress(): XpProgressSummary {
        val floor = prefs.getFinishedBooksRestored()
        val level = RankHook.xpLevelNow(dao, rankPrefs, floor)
        val finished = maxOf(
            dao.allBooks().first().count { !it.isPrivate && isCompleted(it) },
            floor
        )
        val service = rankService()
        val legacyIndex = service.rankIndexFor(finished)
        val isEnglish = prefs.lang.first().startsWith("en")
        val nextLocked = CharacterThemes.All
            .filter { !service.isUnlocked(it) }
            .minByOrNull { XpRank.Titles[it.xpTitle].minXp }
        return XpProgressSummary(
            totalXp = level.xp,
            level = level,
            finishedBooks = finished,
            legacyRankName = RankService.Ranks[legacyIndex].displayName(isEnglish),
            legacyIndex = legacyIndex,
            nextLockedTheme = nextLocked
        )
    }

    /**
     * Unlock conditions for one character theme, in both gates: the legacy
     * finished-books rank and the XP title. A locked theme is locked in
     * both gates (plus no secret), so both progress bars are meaningful.
     */
    suspend fun themeUnlockInfo(theme: CharacterTheme, isEnglish: Boolean): ThemeUnlockInfo {
        val progress = xpProgress()
        val legacy = RankService.Ranks.getOrNull(theme.requiredRank)
        val title = XpRank.Titles.getOrNull(theme.xpTitle)
        return ThemeUnlockInfo(
            legacyDone = progress.finishedBooks,
            legacyNeed = legacy?.minBooks ?: 0,
            legacyRankName = legacy?.displayName(isEnglish) ?: "",
            xpNow = progress.totalXp,
            xpNeed = title?.minXp ?: 0,
            xpTitleName = title?.displayName(isEnglish) ?: ""
        )
    }

    companion object {
        fun applyLocale(context: Context, tag: String) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val res = context.resources
            val config = res.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
        }
    }
}
