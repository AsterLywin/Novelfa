package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.DocxParser
import java.io.File
import java.util.zip.ZipFile

/**
 * The Word engine. Previously import ran the full DOCX parse —
 * unzipping every media file, then walking the whole body with a DOM
 * parser — just to fill the library row. Now import reads only small
 * entries out of the ZIP, scanned as text:
 *
 * - `docProps/core.xml` for the title (a few hundred bytes);
 * - `word/document.xml`, scanned once as text for Heading1/Heading2
 *   paragraph styles, which is exactly how the full parser splits
 *   sections;
 * - the cover, as the first image the body actually references
 *   (embed ids in body order, resolved through the rels entry) —
 *   the same image the full parser used to report, without
 *   extracting any other media entry. No DOM is built at import.
 *
 * The section count is therefore an estimate in one edge case:
 * headings applied through a style definition rather than a direct
 * `pStyle` on the paragraph are not counted here. The first open
 * runs the full parser and corrects the row, exactly as lazy PDFs
 * already did. A document with no text at all fails at import with
 * the same "no readable sections" error as before, not at open.
 *
 * At open, footnotes and endnotes keep the ReadEra behaviour this
 * engine can actually deliver in an HTML reader: references are
 * anchor links to the notes list, and each note links back to its
 * reference. A true "footnote at the bottom of the page" needs a
 * pager-aware layout and is not claimed here — notes sit at the end
 * of their section, as before, but are now reachable both ways.
 */
object DocxEngine : BookEngine {
    override val format: BookFormat = BookFormat.DOCX

    private val headingStyle = Regex(
        """<w:pStyle[^>]*w:val="(Heading[12])""""
    )
    private val textRun = Regex("""<w:t[^>]*>([^<]+)</w:t>""")
    private val coreTitle = Regex("""<(?:dc|dcterms):title[^>]*>([^<]+)</""")
    private val embedId = Regex("""r:embed="([^"]+)"""")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private const val MAX_COVER_BYTES = 5L * 1024 * 1024

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        val fallbackTitle = File(displayName).nameWithoutExtension
            .takeIf { it.isNotBlank() } ?: displayName
        ZipFile(source).use { zf ->
            val documentEntry = zf.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("DOCX has no word/document.xml: $displayName")
            val documentXml = zf.getInputStream(documentEntry).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val headings = headingStyle.findAll(documentXml).toList()
            val hasText = textRun.find(documentXml)?.groupValues?.get(1)
                ?.isNotBlank() == true
            if (!hasText) throw IllegalArgumentException(
                "DOCX has no readable text: $displayName"
            )
            // With no headings there is no "before the first
            // heading" to look at — and substring(0, Int.MAX_VALUE)
            // would throw on exactly those heading-less documents.
            val firstHeadingAt = headings.firstOrNull()?.range?.first
            val textBeforeFirstHeading = firstHeadingAt != null &&
                textRun.find(documentXml.substring(0, firstHeadingAt))
                    ?.groupValues?.get(1)?.isNotBlank() == true
            val sections = when {
                headings.isEmpty() -> 1
                textBeforeFirstHeading -> headings.size + 1
                else -> headings.size
            }
            val title = zf.getEntry("docProps/core.xml")?.let { entry ->
                val core = zf.getInputStream(entry).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                coreTitle.find(core)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotBlank() }
            } ?: fallbackTitle
            return EngineMetadata(title, sections, extractCover(zf, documentXml, scratchDir))
        }
    }

    /**
     * First body-referenced image, in body order. The document XML
     * carries embed ids in reading order; the rels entry maps each
     * id to its media target. Scanning both as text keeps import
     * from unzipping the media folder or building a DOM, while
     * still returning the same cover the full parser reported.
     * Falls back to the first media entry when the document has
     * no rels or no body references at all.
     */
    private fun extractCover(
        zf: ZipFile,
        documentXml: String,
        scratchDir: File
    ): File? {
        val relsXml = zf.getEntry("word/_rels/document.xml.rels")?.let { entry ->
            zf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        fun targetFor(id: String): String? {
            if (relsXml == null) return null
            val escaped = Regex.escape(id)
            return Regex("""<Relationship[^>]*Id="$escaped"[^>]*Target="([^"]+)"""")
                .find(relsXml)?.groupValues?.get(1)
                ?: Regex("""<Relationship[^>]*Target="([^"]+)"[^>]*Id="$escaped"""")
                    .find(relsXml)?.groupValues?.get(1)
        }
        val candidates = mutableListOf<String>()
        for (match in embedId.findAll(documentXml)) {
            val target = targetFor(match.groupValues[1]) ?: continue
            val entryName = when {
                target.startsWith("word/") -> target
                target.startsWith("/") -> target.removePrefix("/")
                else -> "word/$target"
            }
            if (entryName.substringAfterLast('.', "").lowercase() in imageExtensions) {
                candidates += entryName
            }
        }
        if (candidates.isEmpty()) {
            candidates += zf.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("word/media/") }
                .filter { it.substringAfterLast('.', "").lowercase() in imageExtensions }
                .sorted()
        }
        val entryName = candidates.firstOrNull() ?: return null
        val entry = zf.getEntry(entryName) ?: return null
        if (entry.size > MAX_COVER_BYTES) return null
        scratchDir.mkdirs()
        val dest = File(scratchDir, "cover." + entryName.substringAfterLast('.', "jpg"))
        zf.getInputStream(entry).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest.takeIf { it.exists() && it.length() > 0 }
    }

    override suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        return DocxParser.parseFile(
            source,
            outDir,
            chapterFallback,
            displayName,
            cancelCheck
        )
    }
}
