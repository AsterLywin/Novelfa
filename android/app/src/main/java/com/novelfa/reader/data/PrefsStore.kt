package com.novelfa.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prefs")

class PrefsStore(private val context: Context) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val ACCENT = stringPreferencesKey("accent")
        val CHAR_THEME = stringPreferencesKey("charTheme")
        val FONT_SIZE = intPreferencesKey("fontSize")
        val SUPPORT_ENABLED = booleanPreferencesKey("supportEnabled")

        // Task 7 additive keys (RankService prefs "MaxRank"/"SecretThemes").
        val MAX_RANK = stringPreferencesKey("MaxRank")
        val SECRET_THEMES = stringPreferencesKey("SecretThemes")
        // XP rank system additive keys (XpStore, same cache pattern).
        val TOTAL_XP = stringPreferencesKey("TotalXp")
        val XP_LEDGER = stringPreferencesKey("XpLedger")
        val XP_MIGRATED = stringPreferencesKey("XpMigrated")
        val XP_CELEBRATED = stringPreferencesKey("XpCelebratedXp")
        val XP_REKEYED = stringPreferencesKey("XpRekeyed")

        // Task 8 additive keys (PIN hash, support counter, locale, reader defaults).
        val PIN_HASH = stringPreferencesKey("pinHash")
        // PIN brute-force throttle, persisted (an in-memory-only throttle
        // was reset by force-stopping the app, defeating the lockout).
        val PIN_FAILURES = intPreferencesKey("pinFailures")
        val PIN_LOCKED_UNTIL = longPreferencesKey("pinLockedUntil")
        val SUPPORT_SECONDS = intPreferencesKey("supportSeconds")
        val LANG = stringPreferencesKey("Lang")
        val FONT_FAMILY = stringPreferencesKey("fontFamily")
        val READER_LINE_HEIGHT = stringPreferencesKey("readerLineHeight")
        val READER_MARGIN = stringPreferencesKey("readerMargin")
        val READER_SPACING = stringPreferencesKey("readerSpacing")
        val PROFILE_NAME = stringPreferencesKey("profileName")
        val AVATAR = stringPreferencesKey("avatarPath")
        // WPF parity: animated character-theme particle layer (default off,
        // like the desktop client's "0").
        val THEME_PARTICLES = booleanPreferencesKey("themeParticles")
        val GUIDE_SHOWN = booleanPreferencesKey("guideShown")
        // Reading layout mode (replaces the old continuous toggle).
        val READING_MODE = stringPreferencesKey("readingMode")
        // Safe (fast) import: single-read copy + deferred generated
        // covers, toggled from Settings. Default ON: it only changes
        // how import reads and when covers are built, never the book
        // itself, the reader, or books already in the library.
        val FAST_IMPORT = booleanPreferencesKey("fastImport")
        // Backup/restore: lifetime finished-books floor restored from a
        // backup (see BackupService). Effective finished count is
        // max(libraryFinished, floor) so a restored record survives an
        // empty library on a new device; monotonic, never decreases.
        val FINISHED_BOOKS_RESTORED = intPreferencesKey("FinishedBooksRestored")
        // Upstream CollectionRegistry parity: manually-created collection
        // names (including empty ones, which have no books to anchor them).
        val MANUAL_COLLECTIONS = stringSetPreferencesKey("manualCollections")
    }

    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "dark" }
    val accent: Flow<String> = context.dataStore.data.map { it[ACCENT] ?: "#7C3AED" }
    val charTheme: Flow<String> = context.dataStore.data.map { it[CHAR_THEME] ?: "" }
    val fontSize: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE] ?: 18 }
    val supportEnabled: Flow<Boolean> = context.dataStore.data.map { it[SUPPORT_ENABLED] ?: true }

    // Task 8 additive flows (locale + reader defaults; theme/accent/charTheme/
    // fontSize/supportEnabled already exist above).
    val lang: Flow<String> = context.dataStore.data.map { it[LANG] ?: "fa" }
    val fontFamily: Flow<String> = context.dataStore.data.map { it[FONT_FAMILY] ?: "" }
    val readerLineHeight: Flow<String> = context.dataStore.data.map { it[READER_LINE_HEIGHT] ?: "1.8" }
    val readerMargin: Flow<String> = context.dataStore.data.map { it[READER_MARGIN] ?: "16" }
    val readerSpacing: Flow<String> = context.dataStore.data.map { it[READER_SPACING] ?: "0.6" }
    val profileName: Flow<String> = context.dataStore.data.map { it[PROFILE_NAME] ?: "" }
    /** Custom avatar file path; blank = default Novelfa icon. */
    val avatarPath: Flow<String> = context.dataStore.data.map { it[AVATAR] ?: "" }
    /** Lifetime finished-books floor restored from a backup (default 0). */
    val finishedBooksRestored: Flow<Int> =
        context.dataStore.data.map { it[FINISHED_BOOKS_RESTORED] ?: 0 }
    /** Animated character-theme particles behind the library (default off). */
    val themeParticles: Flow<Boolean> = context.dataStore.data.map { it[THEME_PARTICLES] ?: false }

    /** First-run guide shown (set on Done/Skip; never for existing users). */
    val guideShown: Flow<Boolean> = context.dataStore.data.map { it[GUIDE_SHOWN] ?: false }
    /** Reading layout mode key: "paged" (default) or "continuous". */
    val readingMode: Flow<String> = context.dataStore.data.map { it[READING_MODE] ?: "paged" }
    /** Safe import toggle (default ON; see FAST_IMPORT). */
    val fastImport: Flow<Boolean> = context.dataStore.data.map { it[FAST_IMPORT] ?: true }

    /** The appearance values the first frame is painted with. */
    data class Appearance(
        val theme: String,
        val accent: String,
        val charTheme: String,
        val lang: String
    )

    /**
     * One-shot read of the appearance prefs, used by MainActivity BEFORE
     * the first frame: the flows above only emit after composition has
     * started, so seeding [collectAsState] with their built-in defaults
     * painted every cold start in the default dark/purple/fa for a few
     * frames and then snapped to the user's real theme. Reading the
     * (tiny) preferences file once up front removes that flash.
     * Defaults here MUST match the flows above.
     */
    suspend fun appearanceSnapshot(): Appearance {
        val p = context.dataStore.data.first()
        return Appearance(
            theme = p[THEME] ?: "dark",
            accent = p[ACCENT] ?: "#7C3AED",
            charTheme = p[CHAR_THEME] ?: "",
            lang = p[LANG] ?: "fa"
        )
    }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[THEME] = value }
    }

    suspend fun setAccent(value: String) {
        context.dataStore.edit { it[ACCENT] = value }
    }

    suspend fun setCharTheme(value: String) {
        context.dataStore.edit { it[CHAR_THEME] = value }
    }

    suspend fun setFontSize(value: Int) {
        context.dataStore.edit { it[FONT_SIZE] = value }
    }

    suspend fun setSupportEnabled(value: Boolean) {
        context.dataStore.edit { it[SUPPORT_ENABLED] = value }
    }

    // Task 8 additive setters.
    suspend fun setLang(value: String) {
        context.dataStore.edit { it[LANG] = value }
    }

    suspend fun setFontFamily(value: String) {
        context.dataStore.edit { it[FONT_FAMILY] = value }
    }

    suspend fun setReaderLineHeight(value: String) {
        context.dataStore.edit { it[READER_LINE_HEIGHT] = value }
    }

    suspend fun setReaderMargin(value: String) {
        context.dataStore.edit { it[READER_MARGIN] = value }
    }

    suspend fun setReaderSpacing(value: String) {
        context.dataStore.edit { it[READER_SPACING] = value }
    }

    suspend fun setProfileName(value: String) {
        context.dataStore.edit { it[PROFILE_NAME] = value }
    }

    suspend fun setAvatarPath(value: String) {
        context.dataStore.edit { it[AVATAR] = value }
    }

    suspend fun getFinishedBooksRestored(): Int =
        context.dataStore.data.map { it[FINISHED_BOOKS_RESTORED] ?: 0 }.first()

    /** Monotonic: the floor only ever moves up. */
    suspend fun raiseFinishedBooksRestored(value: Int) {
        context.dataStore.edit {
            val cur = it[FINISHED_BOOKS_RESTORED] ?: 0
            if (value > cur) it[FINISHED_BOOKS_RESTORED] = value
        }
    }

    suspend fun setThemeParticles(value: Boolean) {
        context.dataStore.edit { it[THEME_PARTICLES] = value }
    }

    suspend fun setGuideShown(value: Boolean) {
        context.dataStore.edit { it[GUIDE_SHOWN] = value }
    }

    suspend fun getReadingMode(): String =
        context.dataStore.data.map { it[READING_MODE] ?: "paged" }.first()

    suspend fun setReadingMode(value: String) {
        context.dataStore.edit { it[READING_MODE] = value }
    }

    suspend fun setFastImport(value: Boolean) {
        context.dataStore.edit { it[FAST_IMPORT] = value }
    }

    /** Sync read of the fast-import toggle (suspend: never blocks the main thread). */
    suspend fun fastImportNow(): Boolean =
        context.dataStore.data.map { it[FAST_IMPORT] ?: true }.first()

    /** Manually-created collection names (survive even when empty). */
    val manualCollections: Flow<Set<String>> =
        context.dataStore.data.map { it[MANUAL_COLLECTIONS] ?: emptySet() }

    suspend fun getManualCollections(): Set<String> = manualCollections.first()

    suspend fun addManualCollection(name: String) {
        context.dataStore.edit { it[MANUAL_COLLECTIONS] = (it[MANUAL_COLLECTIONS] ?: emptySet()) + name }
    }

    suspend fun removeManualCollection(name: String) {
        context.dataStore.edit { it[MANUAL_COLLECTIONS] = (it[MANUAL_COLLECTIONS] ?: emptySet()) - name }
    }

    suspend fun renameManualCollection(old: String, new: String) {
        context.dataStore.edit {
            it[MANUAL_COLLECTIONS] = (it[MANUAL_COLLECTIONS] ?: emptySet()) - old + new
        }
    }

    /** Per-book reader page theme id (WPF parity); blank = default dark. */
    suspend fun getReaderTheme(bookId: String): String =
        context.dataStore.data.map { it[stringPreferencesKey("readerTheme_$bookId")] ?: "" }.first()

    suspend fun setReaderTheme(bookId: String, themeId: String) {
        context.dataStore.edit { it[stringPreferencesKey("readerTheme_$bookId")] = themeId }
    }

    // Task 7 additive generic string access (used by RankService wiring).
    suspend fun getString(key: Preferences.Key<String>, default: String): String =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    /**
     * Writes several string keys in ONE DataStore transaction, so a pair
     * that must move together (XP ledger + total) can never persist
     * half-written when the process dies between two separate edits.
     */
    suspend fun setStrings(entries: Map<Preferences.Key<String>, String>) {
        context.dataStore.edit { prefs ->
            for ((key, value) in entries) prefs[key] = value
        }
    }

    // Task 8 additive generic int access (used by the SupportTracker wiring).
    suspend fun getInt(key: Preferences.Key<Int>, default: Int): Int =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun getLong(key: Preferences.Key<Long>, default: Long): Long =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun setLong(key: Preferences.Key<Long>, value: Long) {
        context.dataStore.edit { it[key] = value }
    }

    /** Sync read of the support toggle (suspend: never blocks the main thread). */
    suspend fun supportEnabledNow(): Boolean =
        context.dataStore.data.map { it[SUPPORT_ENABLED] ?: true }.first()
}
