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
        // Native comic viewer page background: "dark" (default, the
        // old 0xFF1E1E1E letterbox), "black" or "white".
        val COMIC_BACKGROUND = stringPreferencesKey("comicBackground")
        // Native comic viewer cut margin (default off): trim the
        // detected content box around each page at display time.
        val COMIC_CUT_MARGIN = booleanPreferencesKey("comicCutMargin")
        // Kept margins after auto-cropping, percent 0..100
        // (ComicScreen's set_img_crop_auto_pad_h/v): each side
        // grows by this percent of the larger margin on its axis.
        val COMIC_CROP_PAD_H = intPreferencesKey("comicCropPadH")
        val COMIC_CROP_PAD_V = intPreferencesKey("comicCropPadV")
        // Native comic viewer padding switch (ComicScreen's
        // set_img_padding_yn, default off).
        val COMIC_PADDING_ENABLED = booleanPreferencesKey("comicPaddingEnabled")
        // Padding ratio, percent 0..40 (ComicScreen's
        // set_img_padding_ratio, SeekBar max 400 -> 0..0.4).
        // NOTE: this key briefly held dp values (0/8/16/24) in
        // an unshipped commit; the unit is percent now, and no
        // APK ever shipped the dp meaning, so no migration.
        val COMIC_PAGE_MARGIN = intPreferencesKey("comicPageMarginPct")
        // Native comic viewer page fit (ComicScreen's
        // set_img_fitscreen: 0 off / 1 height / 2 width / 3
        // stretch): "width" (default, the old behaviour), "height",
        // "original" (native pixels, pannable both ways) or
        // "stretch" (fill the screen, aspect ignored). Paged mode
        // only; continuous mode always shows native size.
        val COMIC_FIT_MODE = stringPreferencesKey("comicFitMode")
        // Native comic viewer page direction (ComicScreen's
        // set_img_page_direction_pager): true (default) = right to
        // left, the reading direction of the translated books this
        // viewer was built for; false = left to right.
        val COMIC_DIRECTION_RTL = booleanPreferencesKey("comicDirectionRtl")
        // Native comic viewer sharpen (ComicScreen's
        // set_img_filter_sharp_yn, default off): unsharp masking
        // after crop/upscale, strength = level / 10.
        val COMIC_SHARPEN = booleanPreferencesKey("comicSharpen")
        val COMIC_SHARPEN_LEVEL = intPreferencesKey("comicSharpenLevel")
        // Native comic viewer dual page (ComicScreen's 2p view,
        // default off): in paged mode two pages sit side by side
        // per screen, and a single landscape page is split across
        // the pair (its halves, reading order first). Continuous
        // mode ignores it — a strip is one page wide by
        // definition. Paged only, like fit/direction/zoom.
        val COMIC_DUAL_PAGE = booleanPreferencesKey("comicDualPage")
        // Safe (fast) import: single-read copy + deferred generated
        // covers, toggled from Settings. Default ON: it only changes
        // how import reads and when covers are built, never the book
        // itself, the reader, or books already in the library.
        val FAST_IMPORT = booleanPreferencesKey("fastImport")
        // In-place import: ON = new imports are read where they
        // live instead of copied into filesDir/books (see
        // InPlaceSource). Default OFF: the copy behaviour is the
        // safe one, and the switch only affects imports started
        // after it changes; books already in the library keep
        // their mode forever.
        val IN_PLACE_IMPORT = booleanPreferencesKey("inPlaceImport")
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
    /** Native comic viewer page background: "dark" (default), "black" or "white". */
    val comicBackground: Flow<String> =
        context.dataStore.data.map { it[COMIC_BACKGROUND] ?: "dark" }
    /** Native comic viewer cut margin (default off). */
    val comicCutMargin: Flow<Boolean> =
        context.dataStore.data.map { it[COMIC_CUT_MARGIN] ?: false }
    /** Kept margin after auto-cropping, horizontal percent 0..100 (default 0). */
    val comicCropPadH: Flow<Int> =
        context.dataStore.data.map { it[COMIC_CROP_PAD_H] ?: 0 }
    /** Kept margin after auto-cropping, vertical percent 0..100 (default 0). */
    val comicCropPadV: Flow<Int> =
        context.dataStore.data.map { it[COMIC_CROP_PAD_V] ?: 0 }
    /** Native comic viewer padding switch (default off). */
    val comicPaddingEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[COMIC_PADDING_ENABLED] ?: false }
    /** Native comic viewer padding ratio, percent 0..40 (default 0). */
    val comicPageMargin: Flow<Int> =
        context.dataStore.data.map { it[COMIC_PAGE_MARGIN] ?: 0 }
    val comicFitMode: Flow<String> =
        context.dataStore.data.map { it[COMIC_FIT_MODE] ?: "width" }
    val comicDirectionRtl: Flow<Boolean> =
        context.dataStore.data.map { it[COMIC_DIRECTION_RTL] ?: true }
    val comicSharpen: Flow<Boolean> =
        context.dataStore.data.map { it[COMIC_SHARPEN] ?: false }
    val comicSharpenLevel: Flow<Int> =
        context.dataStore.data.map { it[COMIC_SHARPEN_LEVEL] ?: 5 }
    /** Native comic viewer dual page (default off, paged mode only). */
    val comicDualPage: Flow<Boolean> =
        context.dataStore.data.map { it[COMIC_DUAL_PAGE] ?: false }
    /** Safe import toggle (default ON; see FAST_IMPORT). */
    val fastImport: Flow<Boolean> = context.dataStore.data.map { it[FAST_IMPORT] ?: true }
    /** In-place import toggle (default OFF; see IN_PLACE_IMPORT). */
    val inPlaceImport: Flow<Boolean> = context.dataStore.data.map { it[IN_PLACE_IMPORT] ?: false }

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

    suspend fun setComicBackground(value: String) {
        context.dataStore.edit { it[COMIC_BACKGROUND] = value }
    }

    suspend fun setComicCutMargin(value: Boolean) {
        context.dataStore.edit { it[COMIC_CUT_MARGIN] = value }
    }

    suspend fun setComicCropPadH(value: Int) {
        context.dataStore.edit { it[COMIC_CROP_PAD_H] = value }
    }

    suspend fun setComicCropPadV(value: Int) {
        context.dataStore.edit { it[COMIC_CROP_PAD_V] = value }
    }

    suspend fun setComicPaddingEnabled(value: Boolean) {
        context.dataStore.edit { it[COMIC_PADDING_ENABLED] = value }
    }

    suspend fun setComicPageMargin(value: Int) {
        context.dataStore.edit { it[COMIC_PAGE_MARGIN] = value }
    }
    suspend fun setComicFitMode(value: String) {
        context.dataStore.edit { it[COMIC_FIT_MODE] = value }
    }
    suspend fun setComicDirectionRtl(value: Boolean) {
        context.dataStore.edit { it[COMIC_DIRECTION_RTL] = value }
    }
    suspend fun setComicSharpen(value: Boolean) {
        context.dataStore.edit { it[COMIC_SHARPEN] = value }
    }
    suspend fun setComicSharpenLevel(value: Int) {
        context.dataStore.edit { it[COMIC_SHARPEN_LEVEL] = value }
    }

    suspend fun setComicDualPage(value: Boolean) {
        context.dataStore.edit { it[COMIC_DUAL_PAGE] = value }
    }

    suspend fun setFastImport(value: Boolean) {
        context.dataStore.edit { it[FAST_IMPORT] = value }
    }

    suspend fun setInPlaceImport(value: Boolean) {
        context.dataStore.edit { it[IN_PLACE_IMPORT] = value }
    }

    /** Sync read of the in-place import toggle (suspend: never blocks the main thread). */
    suspend fun inPlaceImportNow(): Boolean =
        context.dataStore.data.map { it[IN_PLACE_IMPORT] ?: false }.first()

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
