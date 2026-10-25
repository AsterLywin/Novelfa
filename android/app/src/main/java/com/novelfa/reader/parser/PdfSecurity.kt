package com.novelfa.reader.parser

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PDF password states, surfaced so the importer can tell "ask the user"
 * apart from "wrong password" and "unsupported encryption" instead of
 * lumping them all into "damaged file".
 */
class PdfPasswordRequiredException :
    Exception("PDF requires a password")

class PdfPasswordWrongException :
    Exception("PDF password is wrong")

class PdfUnsupportedEncryptionException(message: String) :
    Exception(message)

/**
 * Pure crypto for the PDF Standard security handler (ISO 32000-1 §7.6):
 * MD5/SHA digests, RC4, AES, the R2–R4 password/key algorithms and the
 * R5/R6 (AES-256) algorithms, including the iterative R6 hash (2.B).
 * No Android keystore, no third-party dependency — MessageDigest and
 * javax.crypto are platform APIs, and RC4 is implemented here because
 * its availability as a named Cipher varies across providers.
 *
 * Password encoding: R<5 passwords are truncated to 32 bytes, R5/R6 to
 * 127 bytes, both as UTF-8. (The spec's PDFDocEncoding/SASLprep subtleties
 * for non-ASCII passwords are intentionally not chased: ASCII passwords
 * — the overwhelmingly common case — behave identically either way.)
 */
internal object PdfCrypto {

    /** The 32-byte padding string every R<5 password computation uses. */
    val PADDING: ByteArray = intArrayOf(
        0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80,
        0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A
    ).map { it.toByte() }.toByteArray()

    fun md5(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("MD5")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun sha384(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-384").digest(data)

    fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    /** RC4 (symmetric: same function encrypts and decrypts). */
    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var x = 0
        var y = 0
        for (n in data.indices) {
            x = (x + 1) and 0xFF
            y = (y + s[x]) and 0xFF
            val t = s[x]; s[x] = s[y]; s[y] = t
            out[n] = (data[n].toInt() xor s[(s[x] + s[y]) and 0xFF]).toByte()
        }
        return out
    }

    /** AES-ECB, no padding (UE/OE/Perms are exact multiples of 16). */
    fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(data)
    } catch (e: Exception) {
        null
    }

    fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(data)
    } catch (e: Exception) {
        null
    }

    /** AES-CBC with an explicit IV, no padding (caller strips PKCS#7). */
    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(data)
    } catch (e: Exception) {
        null
    }

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(data)
    } catch (e: Exception) {
        null
    }

    /**
     * CBC data with a random 16-byte IV prefix, PKCS#7 padded — the
     * layout AESV2/AESV3 streams and strings use. Null when the padding
     * is malformed (wrong key, or corrupt data).
     */
    fun aesCbcDecryptWithIvPrefix(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.size < 32 || (data.size - 16) % 16 != 0) return null
        val plain = aesCbcDecrypt(
            key, data.copyOfRange(0, 16), data.copyOfRange(16, data.size)
        ) ?: return null
        if (plain.isEmpty()) return null
        val pad = plain.last().toInt() and 0xFF
        if (pad < 1 || pad > 16 || pad > plain.size) return null
        for (i in plain.size - pad until plain.size) {
            if ((plain[i].toInt() and 0xFF) != pad) return null
        }
        return plain.copyOfRange(0, plain.size - pad)
    }

    /** Pads [data] to a multiple of 16 with PKCS#7, then CBC-encrypts. */
    fun aesCbcEncryptWithIvPrefix(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? {
        val pad = 16 - (data.size % 16)
        val padded = data + ByteArray(pad) { pad.toByte() }
        val enc = aesCbcEncrypt(key, iv, padded) ?: return null
        return iv + enc
    }

    /** R<5 password preparation: truncate to 32 bytes, pad to 32. */
    fun padPassword32(password: ByteArray): ByteArray {
        val truncated = password.copyOf(minOf(password.size, 32))
        if (truncated.size == 32) return truncated
        return truncated + PADDING.copyOf(32 - truncated.size)
    }

    /** R5/R6 password preparation: truncate to 127 bytes, no padding. */
    fun truncatePassword127(password: ByteArray): ByteArray =
        password.copyOf(minOf(password.size, 127))

    /**
     * The R6 iterative hash (Algorithm 2.B): K starts as SHA-256, then
     * each round AES-128-CBC-encrypts (password + K + userKey) × 64 and
     * re-hashes with SHA-256/384/512 chosen by E mod 3, until round ≥ 64
     * and E's last byte ≤ round − 32. [userKey] is empty for the user
     * password and the 48-byte U value for the owner password.
     */
    fun r6Hash(password: ByteArray, salt: ByteArray, userKey: ByteArray): ByteArray {
        var k = sha256(password, salt, userKey)
        var e = ByteArray(0)
        var round = 0
        while (true) {
            val base = password + k + userKey
            val k1 = ByteArray(base.size * 64)
            for (i in 0 until 64) base.copyInto(k1, i * base.size)
            e = aesCbcEncrypt(
                k.copyOfRange(0, 16), k.copyOfRange(16, 32), k1
            ) ?: return k.copyOf(32)
            var rem = 0
            for (i in 0 until 16) {
                rem = (rem * 256 + (e[i].toInt() and 0xFF)) % 3
            }
            k = when (rem) {
                0 -> sha256(e)
                1 -> sha384(e)
                else -> sha512(e)
            }
            round++
            if (round >= 64 && (e[e.size - 1].toInt() and 0xFF) <= round - 32) break
        }
        return k.copyOf(32)
    }
}
