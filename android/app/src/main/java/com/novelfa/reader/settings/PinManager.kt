package com.novelfa.reader.settings

import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.library.PinGate
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** Thrown for PIN validation failures; message is user-facing fa text. */
class PinException(messageFa: String) : IllegalArgumentException(messageFa)

/**
 * Sync PIN-hash store. Production bridges Task 3 PrefsStore (DataStore) via
 * [PrefsStorePinPrefs]; tests use [InMemoryPinPrefs]. Mirrors the Task 7
 * RankPrefs bridge pattern (PrefsStore needs a Context and is not
 * unit-testable on plain JVM).
 */
interface PinPrefs {
    fun pinHash(): String?
    fun setPinHash(hash: String?)
}

/** In-memory PinPrefs for tests and previews. */
class InMemoryPinPrefs : PinPrefs {
    private var hash: String? = null
    override fun pinHash(): String? = hash
    override fun setPinHash(hash: String?) {
        this.hash = hash
    }
}

/** Production PinPrefs backed by Task 3 PrefsStore (DataStore). */
class PrefsStorePinPrefs(private val store: PrefsStore) : PinPrefs {
    override fun pinHash(): String? =
        runBlocking { store.getString(PrefsStore.PIN_HASH, "") }.takeIf { it.isNotBlank() }

    override fun setPinHash(hash: String?) {
        runBlocking { store.setString(PrefsStore.PIN_HASH, hash ?: "") }
    }
}

/**
 * PIN gate ported from EpubReader/Services/SettingsService.cs (SHA-256 PIN)
 * + PinWindow.xaml.cs (set ≥4 chars / verify / change flow).
 *
 * Implements Task 5's [PinGate] (do NOT redefine it): [hasPin] reports
 * whether a hash is stored non-blank.
 */
class PinManager(private val prefs: PinPrefs) : PinGate {

    override fun hasPin(): Boolean = !prefs.pinHash().isNullOrBlank()

    /** SHA-256 hex compare (stored hash is trimmed, like C# `.Trim()`). */
    fun verify(pin: String): Boolean {
        val saved = prefs.pinHash()?.trim()
        if (saved.isNullOrEmpty()) return false
        return saved == hashPin(pin.trim())
    }

    /** Stores a new PIN; rejects PINs shorter than 4 chars (fa message). */
    fun setPin(pin: String) {
        val clean = pin.trim()
        if (clean.length < 4) throw PinException("پین باید حداقل ۴ کاراکتر باشه")
        prefs.setPinHash(hashPin(clean))
    }

    /** Verifies the old PIN first, then stores the new one. */
    fun changePin(old: String, new: String) {
        if (!verify(old)) throw PinException("پین اشتباهه")
        setPin(new)
    }

    fun clearPin() {
        prefs.setPinHash(null)
    }

    companion object {
        /**
         * EXACT port of C# HashPin: UTF-8 bytes -> SHA-256 -> lowercase hex
         * ("x2" per byte). Each byte is masked with 0xFF before formatting so
         * negative bytes render as 2 chars, not 8 (Java sign extension).
         */
        fun hashPin(pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
