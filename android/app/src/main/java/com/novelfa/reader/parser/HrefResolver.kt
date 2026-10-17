package com.novelfa.reader.parser

import java.io.File
import java.net.URLDecoder

object HrefResolver {
    fun resolve(folder: File, href: String): File {
        val clean = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        // Confine the href to the extraction dir (same pattern as
        // EpubParser.findOpfFile): a crafted EPUB with hrefs like
        // "../../../../databases/reader.db" must not escape and get
        // rendered as a chapter.
        val canonicalDir = folder.canonicalPath + File.separatorChar
        val decodedFile = File(folder, decoded.replace('/', File.separatorChar))
        if (decodedFile.canonicalPath.startsWith(canonicalDir)) return decodedFile
        val cleanFile = File(folder, clean.replace('/', File.separatorChar))
        if (cleanFile.canonicalPath.startsWith(canonicalDir)) return cleanFile
        // Traversal attempt: fall back to the bare file name inside the folder.
        return File(folder, File(decoded).name)
    }
}
