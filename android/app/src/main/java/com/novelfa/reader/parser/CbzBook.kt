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
 * - Chapter folders ARE split into sections ([chaptersForEntries]):
 *   each folder (after stripping a path every folder shares, e.g.
 *   the series folder) is one chapter starting at its first page,
 *   and the native viewer offers that list as a one-tap jump. A
 *   flat archive (no folders) has no chapters and reads exactly
 *   as before; root-level pages belong to no chapter.
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
     * One chapter of a foldered comic: the folder name as stored
     * in the archive (book data, never translated) and the index,
     * into the natural page order, of that chapter's first page.
     * The native viewer jumps to [firstPageIndex] on selection;
     * zoom, the page slider and the reading mode are untouched by
     * the jump, so the reader lands on the chapter's first page
     * in exactly the state it was already reading in.
     */
    data class CbzChapter(
        val title: String,
        val firstPageIndex: Int
    )

    /**
     * Chapters for an archive, from its image entries alone (the
     * caller may pass the raw ZIP listing; non-images are filtered
     * and the rest is put in natural order here, so the result is
     * the same as for [imageEntryNames]).
     *
     * Rules:
     * - Chapter = the first folder segment of a page's parent
     *   folder, after stripping the path segments EVERY foldered
     *   page shares: `Series/Chapter 01/...` and
     *   `Series/Chapter 02/...` give chapters `Chapter 01` and
     *   `Chapter 02`, not two chapters both called `Series`.
     * - Deeper nesting stays inside its chapter: `Ch 1/part 1/`
     *   and `Ch 1/part 2/` are both chapter `Ch 1`.
     * - Root-level pages (a cover before the first folder) belong
     *   to no chapter; they still count as pages, so a chapter
     *   after them starts past index 0.
     * - A flat archive, or an archive whose pages all sit in one
     *   single folder, yields no chapters: there is nothing to
     *   jump between, and the viewer shows no chapter control.
     * - Chapters come out in first-appearance order, which IS
     *   reading order because the pages are naturally sorted.
     */
    fun chaptersForEntries(entryNames: List<String>): List<CbzChapter> {
        val pages = entryNames.filter { isImageEntry(it) }.sortedWith(::compareNatural)
        if (pages.isEmpty()) return emptyList()
        val parents = pages.map { it.substringBeforeLast('/', "") }
        val foldered = parents.filter { it.isNotEmpty() }
        val common: List<String> = if (foldered.isNotEmpty() && foldered.size == parents.size) {
            foldered.map { it.split('/') }.reduce { acc, parts ->
                acc.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
            }
        } else {
            emptyList()
        }
        val firstPage = linkedMapOf<String, Int>()
        for ((index, parent) in parents.withIndex()) {
            val stripped = if (common.isEmpty()) {
                parent
            } else {
                parent.split('/').drop(common.size).joinToString("/")
            }
            // substringBefore with its default: a single-segment
            // folder IS the chapter name (an explicit "" here
            // would have thrown single-segment chapters away).
            val key = stripped.substringBefore('/')
            if (key.isNotEmpty() && key !in firstPage) {
                firstPage[key] = index
            }
        }
        return firstPage.map { (title, index) -> CbzChapter(title, index) }
    }

    /** Chapters for an archive file, in reading order. */
    fun chapters(zipFile: File): List<CbzChapter> =
        chaptersForEntries(imageEntryNames(zipFile))

    /**
     * Natural order, ComicScreen's way: compare path SEGMENT by
     * segment (split on '/'), each segment naturally (runs of
     * digits as numbers, 2 < 10, the rest case-insensitive). A
     * path that is a prefix of the other sorts first, so a folder
     * sorts before its contents instead of after a same-prefix
     * file (`a/b` before `a-b`, where a flat comparison would
     * put the '-' file first because '-' < '/'). Splitting on
     * digit runs keeps `page-2` before `page-10` and keeps
     * `chapter-01/page-03` grouped under its folder.
     */
    internal fun compareNatural(a: String, b: String): Int {
        val aSegs = a.split('/')
        val bSegs = b.split('/')
        val n = minOf(aSegs.size, bSegs.size)
        for (i in 0 until n) {
            val cmp = compareNaturalSegment(aSegs[i], bSegs[i])
            if (cmp != 0) return cmp
        }
        return aSegs.size.compareTo(bSegs.size)
    }

    private fun compareNaturalSegment(a: String, b: String): Int {
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
