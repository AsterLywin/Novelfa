package com.novelfa.reader.data

import android.content.Context
import java.io.File

class FontStore(private val context: Context) {

    private fun fontsDir(): File = File(context.filesDir, "fonts").apply { mkdirs() }

    fun addFont(name: String, src: File): File {
        val dest = File(fontsDir(), name)
        src.copyTo(dest, overwrite = true)
        return dest
    }

    fun list(): List<File> = fontsDir().listFiles()?.toList() ?: emptyList()

    fun isAvailable(f: File): Boolean = f.exists()
}
