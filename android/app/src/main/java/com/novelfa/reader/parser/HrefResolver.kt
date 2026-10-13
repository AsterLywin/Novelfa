package com.novelfa.reader.parser

import java.io.File
import java.net.URLDecoder

object HrefResolver {
    fun resolve(folder: File, href: String): File {
        val clean = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val decodedFile = File(folder, decoded.replace('/', File.separatorChar))
        if (decodedFile.exists()) return decodedFile
        return File(folder, clean.replace('/', File.separatorChar))
    }
}
