package com.novelfa.reader.parser

import com.novelfa.reader.model.Chapter
import com.novelfa.reader.model.ParsedBook
import java.io.File

/**
 * The on-disk shape of a converted (non-EPUB) book: one XHTML file per
 * section in a directory, plus optional sidecar files (DOCX images live
 * in a `media/` subfolder, referenced relatively from the chapters).
 *
 * Both the importer (fresh parse, to count sections and read the title)
 * and the reader (open path, cached under `epub_open/<bookId>`) use this
 * shape, so a converted book opens exactly like an EPUB's extracted tree:
 * [Chapter.file] points at a chapter file and the reader's existing
 * `prepareChapterBody` pipeline (per-paragraph RTL/LTR stamping, image
 * absolutizing) applies unchanged.
 */
object ConvertedBook {

    /** One reader section: a drawer title and its HTML body fragment. */
    data class Section(val title: String, val bodyHtml: String)

    /** Sections longer than this are split at a paragraph boundary. */
    const val MAX_SECTION_CHARS = 12_000

    /**
     * Writes [sections] as `ch0001.xhtml`… into [outDir] (created if
     * needed) and returns the [ParsedBook] the reader consumes. Each file
     * carries its title in a `<title>` element so [read] can rebuild the
     * drawer titles later without re-parsing the source file.
     */
    fun write(
        outDir: File,
        title: String,
        sections: List<Section>,
        coverFile: File? = null
    ): ParsedBook {
        outDir.mkdirs()
        val chapters = sections.mapIndexed { i, section ->
            // Locale.US: in a Persian locale %d would emit Eastern-Arabic
            // digits, which [read]'s ch\d+ match would then miss.
            val file = File(outDir, "ch%04d.xhtml".format(java.util.Locale.US, i + 1))
            file.writeText(
                "<html><head><meta charset=\"utf-8\"><title>" +
                    escape(section.title) + "</title></head><body>" +
                    section.bodyHtml + "</body></html>",
                Charsets.UTF_8
            )
            Chapter(title = section.title, file = file)
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = coverFile)
    }

    /**
     * Rebuilds a [ParsedBook] from a directory previously written by
     * [write], without touching the source file. Null when the directory
     * holds no chapter files (caller falls back to a fresh parse).
     */
    fun read(
        outDir: File,
        fileName: String,
        chapterFallback: (Int) -> String
    ): ParsedBook? {
        val files = outDir.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("ch\\d+\\.xhtml")) }
            ?.sortedBy { it.name }
            ?: return null
        if (files.isEmpty()) return null
        val title = File(fileName).nameWithoutExtension
        val chapters = files.mapIndexed { i, file ->
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: ""
            val found = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.let { unescape(it) }
                ?.trim()?.takeIf { it.isNotBlank() }
            Chapter(title = found ?: chapterFallback(i + 1), file = file)
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = null)
    }

    /**
     * Splits a long section body at paragraph boundaries so no section
     * exceeds [MAX_SECTION_CHARS]. Later chunks keep the section title
     * with a language-neutral `(2)`, `(3)`… suffix — no hardcoded words
     * in either UI language.
     */
    fun chunkSections(sections: List<Section>): List<Section> {
        val out = mutableListOf<Section>()
        for (section in sections) {
            if (section.bodyHtml.length <= MAX_SECTION_CHARS) {
                out += section
                continue
            }
            val paragraphs = section.bodyHtml.split("(?=<p[ >])".toRegex())
                .filter { it.isNotBlank() }
            var current = StringBuilder()
            var chunk = 1
            fun flush() {
                if (current.isNotEmpty()) {
                    val title = if (chunk == 1) section.title
                    else "${section.title} ($chunk)"
                    out += Section(title, current.toString())
                    current = StringBuilder()
                    chunk++
                }
            }
            for (p in paragraphs) {
                if (current.isNotEmpty() &&
                    current.length + p.length > MAX_SECTION_CHARS
                ) {
                    flush()
                }
                current.append(p)
            }
            flush()
        }
        return out
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")
}
