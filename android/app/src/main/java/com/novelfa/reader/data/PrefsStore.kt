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
    }

    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "dark" }
    val accent: Flow<String> = context.dataStore.data.map { it[ACCENT] ?: "#7C3AED" }
    val charTheme: Flow<String> = context.dataStore.data.map { it[CHAR_THEME] ?: "" }
    val fontSize: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE] ?: 18 }
    val supportEnabled: Flow<Boolean> = context.dataStore.data.map { it[SUPPORT_ENABLED] ?: true }

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

    // Task 7 additive generic string access (used by RankService wiring).
    suspend fun getString(key: Preferences.Key<String>, default: String): String =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }
}
