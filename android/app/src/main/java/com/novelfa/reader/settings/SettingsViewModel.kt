package com.novelfa.reader.settings

import android.content.Context
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.theme.RankPrefs
import com.novelfa.reader.theme.RankService
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Rank summary for the settings screen. */
data class RankSummary(
    val completed: Int,
    val currentName: String,
    val nextName: String?,
    val remaining: Int
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
    suspend fun setReaderLineHeight(value: String) = prefs.setReaderLineHeight(value)
    suspend fun setReaderMargin(value: String) = prefs.setReaderMargin(value)
    suspend fun setReaderSpacing(value: String) = prefs.setReaderSpacing(value)
    suspend fun setSupportEnabled(value: Boolean) {
        prefs.setSupportEnabled(value)
        support.setEnabled(value)
    }

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

    suspend fun currentRank(): RankSummary {
        val done = dao.allBooks().first().count { !it.isPrivate && isCompleted(it) }
        val service = rankService()
        val idx = service.rankIndexFor(done)
        val current = RankService.Ranks[idx]
        val next = RankService.Ranks.getOrNull(idx + 1)
        return RankSummary(
            completed = done,
            currentName = current.nameFa,
            nextName = next?.nameFa,
            remaining = if (next != null) (next.minBooks - done).coerceAtLeast(0) else 0
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
