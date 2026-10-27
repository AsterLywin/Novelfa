package com.novelfa.reader.parser

import java.io.File
import java.security.MessageDigest

/**
 * The reader-open cache stamp, shared by the importer (which now fills
 * the cache at import time, so the first open never re-parses) and the
 * reader (which validates it on open). One stamp file, `.src_stamp`,
 * lives beside the converted/extracted chapters in
 * `filesDir/epub_open/<bookId>/`.
 *
 * The old stamp was `mtime|length` only: a file replaced with the same
 * length and a preserved mtime (backup restore, same-size edit) silently
 * reused a stale cache. This stamp adds a fingerprint of the first and
 * last 64 KiB — cheap (no full-file hash on every open) yet changed by
 * essentially any real edit, including same-size ones in the middle
 * only when both sampled regions happen to be untouched.
 */
object CacheStamp {

    const val STAMP_FILE_NAME = ".src_stamp"
    private const val SAMPLE_BYTES = 64 * 1024

    fun stampFor(source: File): String {
        val headTail = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val len = source.length()
            source.inputStream().use { input ->
                val buf = ByteArray(8192)
                var remaining = minOf(len, SAMPLE_BYTES.toLong())
                while (remaining > 0) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    remaining -= n
                }
            }
            if (len > SAMPLE_BYTES) {
                java.io.RandomAccessFile(source, "r").use { raf ->
                    raf.seek(len - SAMPLE_BYTES)
                    val buf = ByteArray(8192)
                    var remaining = SAMPLE_BYTES.toLong()
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = raf.read(buf, 0, want)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        remaining -= n
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: "unreadable"
        return source.lastModified().toString() + "|" + source.length() + "|" + headTail
    }

    fun writeStamp(cacheDir: File, source: File) {
        cacheDir.mkdirs()
        runCatching {
            File(cacheDir, STAMP_FILE_NAME).writeText(stampFor(source), Charsets.UTF_8)
        }
    }

    fun isValidStamp(cacheDir: File, source: File): Boolean {
        val stamp = File(cacheDir, STAMP_FILE_NAME)
        if (!stamp.isFile) return false
        val text = runCatching { stamp.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return text == stampFor(source)
    }
}
