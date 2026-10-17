package com.novelfa.reader.settings

import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.library.PinGate
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thrown for PIN validation failures; message is user-facing fa text. */
class PinException(messageFa: String) : IllegalArgumentException(messageFa)

/**
 * Sync PIN-hash store. Production bridges Task 3 PrefsStore (DataStore) via
 * [PrefsStorePinPrefs]; tests use [InMemoryPinPrefs]. Mirrors the Task 7
 * RankPrefs bridge pattern (PrefsStore needs a Context and is not
 * unit-testable on plain JVM).
 */
interface PinPrefs {
    suspend fun pinHash(): String?
    suspend fun setPinHash(hash: String?)
}

/** In-memory PinPrefs for tests and previews. */
class InMemoryPinPrefs : PinPrefs {
    private var hash: String? = null
    override suspend fun pinHash(): String? = hash
    override suspend fun setPinHash(hash: String?) {
        this.hash = hash
    }
}

/** Production PinPrefs backed by Task 3 PrefsStore (DataStore). Suspend: never blocks the main thread. */
class PrefsStorePinPrefs(private val store: PrefsStore) : PinPrefs {
    override suspend fun pinHash(): String? =
        store.getString(PrefsStore.PIN_HASH, "").takeIf { it.isNotBlank() }

    override suspend fun setPinHash(hash: String?) {
        store.setString(PrefsStore.PIN_HASH, hash ?: "")
    }
}

/**
 * PIN gate ported from EpubReader/Services/SettingsService.cs (SHA-256 PIN)
 * + PinWindow.xaml.cs (set ≥4 chars / verify / change flow).
 *
 * Implements Task 5's [PinGate] (do NOT redefine it): [hasPin] reports
 * whether a hash is stored non-blank.
 *
 * Security note: new PINs are stored as salted PBKDF2-HMAC-SHA256
 * (`v2$iterations$b64salt$b64hash`). A 4-digit PIN hashed with a single
 * unsalted SHA-256 round (the old format, kept for C# parity) falls to an
 * offline brute force in milliseconds, so [setPin] never writes that format
 * anymore. [verify] still accepts legacy hashes and transparently upgrades
 * them to v2 on the next successful unlock.
 */
class PinManager(private val prefs: PinPrefs) : PinGate {

    override suspend fun hasPin(): Boolean = !prefs.pinHash().isNullOrBlank()

    /**
     * Verifies the PIN against the stored hash (v2 or legacy). On a
     * successful legacy verification the stored hash is upgraded to v2.
     * The input PIN is hashed RAW, exactly like C# VerifyPin/HashPin — no trim.
     * CPU-bound PBKDF2 runs on Dispatchers.Default, never the caller's thread.
     */
    suspend fun verify(pin: String): Boolean = withContext(Dispatchers.Default) {
        val saved = prefs.pinHash()?.trim()
        if (saved.isNullOrEmpty()) return@withContext false
        if (!verifyHash(pin, saved)) return@withContext false
        if (!isV2(saved)) {
            prefs.setPinHash(hashPinSecure(pin))
        }
        true
    }

    /** Stores a new PIN (always v2); rejects PINs shorter than 4 chars (fa message). Raw input, like C#. */
    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        if (pin.length < 4) throw PinException("پین باید حداقل ۴ کاراکتر باشه")
        prefs.setPinHash(hashPinSecure(pin))
    }

    /** Verifies the old PIN first, then stores the new one. */
    suspend fun changePin(old: String, new: String) = withContext(Dispatchers.Default) {
        if (!verify(old)) throw PinException("پین اشتباهه")
        setPin(new)
    }

    suspend fun clearPin() {
        prefs.setPinHash(null)
    }

    companion object {
        private const val HASH_VERSION = "v2"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32 // SHA-256

        /**
         * LEGACY port of C# HashPin: UTF-8 bytes -> SHA-256 -> lowercase hex
         * ("x2" per byte). Each byte is masked with 0xFF before formatting so
         * negative bytes render as 2 chars, not 8 (Java sign extension).
         * Kept for the C# parity unit test and for verifying (then upgrading)
         * hashes written by older installs. Never used for new PINs.
         */
        fun hashPin(pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }

        /**
         * Salted PBKDF2-HMAC-SHA256 hash, format
         * `v2$iterations$base64(salt)$base64(hash)`. Random 16-byte salt per
         * PIN; 120k iterations (~tens of ms on a phone, milliseconds-proof
         * against offline brute force of short PINs).
         */
        fun hashPinSecure(pin: String): String {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val hash = pbkdf2(pin.toCharArray(), salt, PBKDF2_ITERATIONS)
            val enc = Base64.getEncoder()
            return "$HASH_VERSION\$$PBKDF2_ITERATIONS\$${enc.encodeToString(salt)}\$${enc.encodeToString(hash)}"
        }

        /** Verifies [pin] against a stored hash in either v2 or legacy format. */
        fun verifyHash(pin: String, stored: String): Boolean {
            val s = stored.trim()
            if (isV2(s)) {
                val parts = s.split("$")
                if (parts.size != 4) return false
                val iterations = parts[1].toIntOrNull() ?: return false
                val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull()
                    ?: return false
                val want = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
                    ?: return false
                if (salt.size != SALT_BYTES || want.size != HASH_BYTES) return false
                val got = pbkdf2(pin.toCharArray(), salt, iterations.coerceAtLeast(1))
                // Constant-time compare: no early-exit timing signal.
                return MessageDigest.isEqual(got, want)
            }
            // Legacy: single unsalted SHA-256 hex (C# parity).
            return s == hashPin(pin)
        }

        private fun isV2(stored: String): Boolean = stored.startsWith("$HASH_VERSION\$")

        private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, iterations, HASH_BYTES * 8)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}
