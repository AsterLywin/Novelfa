package com.novelfa.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

        // Task 8 additive keys (PIN hash, support counter, locale, reader defaults).
        val PIN_HASH = stringPreferencesKey("pinHash")
        val SUPPORT_SECONDS = intPreferencesKey("supportSeconds")
        val LANG = stringPreferencesKey("Lang")
        val FONT_FAMILY = stringPreferencesKey("fontFamily")
        val READER_LINE_HEIGHT = stringPreferencesKey("readerLineHeight")
        val READER_MARGIN = stringPreferencesKey("readerMargin")
        val READER_SPACING = stringPreferencesKey("readerSpacing")
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

    // Task 7 additive generic string access (used by RankService wiring).
    suspend fun getString(key: Preferences.Key<String>, default: String): String =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    // Task 8 additive generic int access (used by the SupportTracker wiring).
    suspend fun getInt(key: Preferences.Key<Int>, default: Int): Int =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    /** Sync read of the support toggle for the runBlocking SupportPrefs bridge. */
    suspend fun supportEnabledNow(): Boolean =
        context.dataStore.data.map { it[SUPPORT_ENABLED] ?: true }.first()
}
