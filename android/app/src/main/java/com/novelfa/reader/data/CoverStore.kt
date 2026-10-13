package com.novelfa.reader.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

class CoverStore(private val context: Context) {

    fun saveCover(src: File, epubPath: String): File {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val dest = File(coversDir, coverFileName(epubPath, coverExtension(src)))
        if (!dest.exists()) {
            src.copyTo(dest, overwrite = true)
        }
        return dest
    }

    companion object {
        fun coverExtension(src: File): String {
            val ext = src.extension
            return if (ext.isBlank()) ".jpg" else ".$ext"
        }

        fun coverFileName(epubPath: String, extension: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(epubPath.lowercase().toByteArray(Charsets.UTF_8))
            val key = hash.joinToString("") { "%02x".format(it) }
            return "cover_$key$extension"
        }
    }
}
