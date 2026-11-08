package com.novelfa.reader.parser

/**
 * JNI bridge to the native RAR engine (libnovelfa_rar.so, built
 * from public unrar source — see app/src/main/cpp; NO junrar).
 *
 * Graceful degradation, the AvifDecoder contract: if the native
 * lib is absent (a build without the .so, or a device ABI outside
 * the cmake ABI filter), [available] is false and every caller
 * must fail with an honest localized message — never a crash, and
 * every other format is unaffected. The external functions need
 * no .so at compile time; they are only ever called behind the
 * [available] guard (see [RarEngine] and the viewer source).
 *
 * Passwords are session-only, never stored — the same rule as
 * PDF/ZIP passwords: the viewer holds the CharArray for one open
 * and converts it to a transient String per native call.
 */
object RarNative {
    val available: Boolean = runCatching { System.loadLibrary("novelfa_rar"); true }.getOrDefault(false)
    external fun nListEntries(archivePath: String): Array<RarEntry>?
    external fun nExtractEntryBytes(archivePath: String, password: String?, name: String, headerOffset: Long, solid: Boolean): ByteArray?
    external fun nExtractEntryToFile(archivePath: String, password: String?, name: String, headerOffset: Long, solid: Boolean, destPath: String): Int
    external fun nGetRarVersion(archivePath: String): Int // 4, 5, or 0 = unknown/not a RAR
    external fun nGetVersion(): String
}

data class RarEntry(val name: String, val unpackedSize: Long, val headerOffset: Long, val isSolid: Boolean, val isDirectory: Boolean)
