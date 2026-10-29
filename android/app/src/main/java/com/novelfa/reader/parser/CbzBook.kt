package com.novelfa.reader.parser

import java.io.File
import java.util.zip.ZipFile

/**
 * CBZ / plain-ZIP image books (manhwa, manga, comics) — the
 * ComicScreen model, implemented from the open CBZ format, not from
 * any other reader's code: a CBZ is just a ZIP whose entries are
 * page images, in reading order.
 *
 * Rules, shared by the importer (validation + page count) and the
 * native viewer (on-demand decode):
 * - Only image entries count: jpg/jpeg, png, webp, gif, bmp.
 *   Anything else (ComicInfo.xml, covers in other formats, text
 *   files) is ignored, never a page.
 * - Directories, macOS `__MACOSX/` junk and dot-files are ignored.
 * - Order is NATURAL, not lexicographic: `page-2.jpg` before
 *   `page-10.jpg`, and folder paths sort before their contents, so
 *   a zip with chapter folders still reads in order when flattened.
 *   (Chapter folders are not split into sections in v1; the flat
 *   page order is what a vertical manhwa reader scrolls through.)
 * - Nothing is extracted at import: the original archive is stored
 *   as-is and pages are decoded on demand at open time, exactly
 *   like the native PDF viewer. A 500-page manhwa therefore imports
 *   in seconds and costs no extracted-JPEG storage.
 * - CBR (RAR) is deliberately NOT supported: it needs a RAR
 *   decoder, which is a separate dependency. A .cbr file fails
 *   import with the unsupported-format message, not silently.
 */
object CbzBook {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun isImageEntry(name: String): Boolean {
        if (name.endsWith('/')) return false
        val base = name.substringAfterLast('/')
        if (base.startsWith('.')) return false
        if (name.startsWith("__MACOSX/")) return false
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * Sorted image entry names for [zipFile]. Throws on a corrupt
     * or non-ZIP file (the importer's generic catch turns that into
     * a localized import error); returns an empty list only when
     * the archive is valid but holds no images.
     */
    fun imageEntryNames(zipFile: File): List<String> {
        ZipFile(zipFile).use { zip ->
            return zip.entries().toList()
                .map { it.name }
                .filter { isImageEntry(it) }
                .sortedWith(::compareNatural)
        }
    }

    /**
     * Natural order: runs of digits compare as numbers (2 < 10),
     * everything else compares case-insensitively. Splitting on
     * digit runs keeps `page-2` before `page-10` and keeps
     * `chapter-01/page-03` grouped under its folder.
     */
    internal fun compareNatural(a: String, b: String): Int {
        val aParts = splitNatural(a)
        val bParts = splitNatural(b)
        val n = minOf(aParts.size, bParts.size)
        for (i in 0 until n) {
            val x = aParts[i]
            val y = bParts[i]
            val cmp = if (x is Long && y is Long) {
                x.compareTo(y)
            } else {
                x.toString().compareTo(y.toString(), ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)
    }

    private fun splitNatural(s: String): List<Any> {
        val out = mutableListOf<Any>()
        var i = 0
        while (i < s.length) {
            if (s[i].isDigit()) {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                val digits = s.substring(i, j)
                out += digits.toLongOrNull() ?: digits
                i = j
            } else {
                var j = i
                while (j < s.length && !s[j].isDigit()) j++
                out += s.substring(i, j)
                i = j
            }
        }
        return out
    }
}
