package com.novelfa.reader.parser

import com.novelfa.reader.model.ParsedBook
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Single dispatch for every non-EPUB format, shared by the importer
 * (fresh parse, to count sections and read the title) and the reader
 * (open path, into the cached `epub_open/<bookId>` directory).
 * EPUB keeps its own path ([EpubParser] over an extracted tree) and is
 * rejected here so a caller can never silently route it through a
 * converter that would drop its images, styles and spine order.
 */
object BookConverter {

    suspend fun parse(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        /**
         * Original file name from the import picker (e.g. "My Book.txt").
         * Every parser below falls back to its file-name argument when
         * the document carries no internal title — and the stored
         * source file is named "$uuid.<ext>", so without this the book
         * title becomes the UUID. Callers that know the original name
         * (importer, reader open path) must pass it; the default keeps
         * the old behaviour for callers that don't.
         */
        titleFallback: String? = null,
        // Progress for the reader's loading UI: (pages done, pages
        // total) of the PDF text parse / image render. Other formats
        // convert fast enough not to need it; PDF is the slow one.
        onProgress: ((Int, Int) -> Unit)? = null
    ): ParsedBook {
        val fallbackName = titleFallback?.takeIf { it.isNotBlank() } ?: source.name
        return when (BookFormat.detect(source)) {
            BookFormat.TXT -> TextBookParser.parseText(
                source.readBytes(), fallbackName, outDir, chapterFallback
            )
            BookFormat.MARKDOWN -> TextBookParser.parseMarkdown(
                source.readBytes(), fallbackName, outDir, chapterFallback
            )
            BookFormat.PDF -> parsePdf(source, outDir, chapterFallback, fallbackName, onProgress)
            BookFormat.DOCX -> DocxParser.parseFile(source, outDir, chapterFallback, fallbackName)
            else -> throw IllegalArgumentException(
                "Unsupported book format: ${source.name}"
            )
        }
    }

    /**
     * PDF: text first, images as fallback. A scanned / image-only PDF
     * yields zero text chapters — instead of failing the import with
     * "no readable sections", its pages are rendered as images
     * ([PdfImageBook], the ComicScreen approach: a PDF page is a page
     * image, not a text flow). A text parse that throws is given the
     * same chance, since the platform renderer has its own parser and
     * can open files the text extractor rejects — except password
     * problems, which are resolved upstream and must never be masked
     * as an image failure. If rendering also fails, the original text
     * error (when there is one) is the honest one to report.
     */
    private suspend fun parsePdf(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        fallbackName: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ParsedBook {
        // Cancellation hook for the blocking text parse (see PdfParser):
        // without it, Cancel could not interrupt a big text PDF. The
        // context is captured once here (this function is suspend);
        // the hook itself just throws when that job is cancelled.
        val ctx = currentCoroutineContext()
        val cancelCheck = { ctx.ensureActive() }
        val text = runCatching {
            PdfParser.parseDetailed(source.readBytes(), fallbackName, outDir, chapterFallback, null, cancelCheck, onProgress)
        }
        if (text.isSuccess) {
            val (parsed, hasText, pageTexts) = text.getOrThrow()
            if (parsed.chapters.isNotEmpty()) {
                val mixed = hasText.any { it } && hasText.any { !it }
                if (!mixed) return parsed
                // MIXED book (some pages text, some scanned): the old
                // code returned the text-only book and silently dropped
                // every image page. Build it page by page instead:
                // consecutive text pages group into a section (bounded
                // like the text path), consecutive image pages group
                // into an image section (chunkSections splits it, as
                // for a fully scanned book). More than 1000 image
                // pages would blow up storage/time like a fully scanned
                // book over the cap, so that case keeps the text book.
                val imagePages = hasText.withIndex().filter { !it.value }.map { it.index }
                if (imagePages.size > PdfImageBook.MAX_IMAGE_PAGES) return parsed
                try {
                    return buildMixedPdf(
                        source, outDir, parsed.title, hasText, pageTexts, chapterFallback,
                        onProgress
                    )
                } catch (e: Exception) {
                    // A failed mixed build must not lose the text book
                    // that already parsed: rebuild it cleanly and
                    // return that instead of failing the whole import.
                    outDir.listFiles()
                        ?.filter { it.isFile && it.name.matches(Regex("ch\\d+\\.xhtml")) }
                        ?.forEach { it.delete() }
                    File(outDir, "media").deleteRecursively()
                    return PdfParser.parse(source.readBytes(), fallbackName, outDir, chapterFallback, null, cancelCheck)
                }
            }
        } else {
            val err = text.exceptionOrNull()
            if (err is PdfPasswordRequiredException ||
                err is PdfPasswordWrongException ||
                err is PdfUnsupportedEncryptionException
            ) {
                throw err
            }
        }
        val title = text.getOrNull()?.first?.title ?: File(fallbackName).nameWithoutExtension
        try {
            return PdfImageBook.render(source, outDir, title, onProgress)
        } catch (renderErr: Exception) {
            throw text.exceptionOrNull() ?: renderErr
        }
    }

    private suspend fun buildMixedPdf(
        source: File,
        outDir: File,
        title: String,
        hasText: List<Boolean>,
        pageTexts: List<List<String>>,
        chapterFallback: (Int) -> String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ParsedBook {
        // The text parse above already wrote chapter files into
        // outDir; the mixed build replaces them, so stale chapters
        // from the text book must not survive (a shorter mixed book
        // would otherwise keep the text book's tail chapters).
        outDir.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("ch\\d+\\.xhtml")) }
            ?.forEach { it.delete() }
        val imagePages = hasText.withIndex().filter { !it.value }.map { it.index }
        if (imagePages.isNotEmpty()) {
            // Open-phase preload only (first 20 image pages); the
            // reader's background pass renders the rest while the
            // user reads, exactly like the fully-scanned path.
            PdfImageBook.renderPages(
                source, outDir,
                imagePages.take(PdfImageBook.OPEN_PRELOAD_PAGES), onProgress
            )
        }
        val sections = mutableListOf<ConvertedBook.Section>()
        var textBody = StringBuilder()
        var imageBody = StringBuilder()
        fun flushText() {
            if (textBody.isNotEmpty()) {
                sections += ConvertedBook.Section(
                    chapterFallback(sections.size + 1), textBody.toString()
                )
                textBody = StringBuilder()
            }
        }
        fun flushImages() {
            if (imageBody.isNotEmpty()) {
                sections += ConvertedBook.Section(
                    chapterFallback(sections.size + 1), imageBody.toString()
                )
                imageBody = StringBuilder()
            }
        }
        for (i in hasText.indices) {
            if (hasText[i]) {
                flushImages()
                for (para in pageTexts[i]) {
                    val html = "<p>" + ConvertedBook.escape(para) + "</p>"
                    if (textBody.isNotEmpty() &&
                        textBody.length + html.length > ConvertedBook.MAX_SECTION_CHARS
                    ) {
                        flushText()
                    }
                    textBody.append(html)
                }
            } else {
                flushText()
                imageBody.append(
                    "<p><img src=\"media/" + PdfImageBook.fileNameFor(i) + "\"></p>"
                )
                if (imageBody.length > ConvertedBook.MAX_SECTION_CHARS) flushImages()
            }
        }
        flushText()
        flushImages()
        return ConvertedBook.write(
            outDir, title, ConvertedBook.chunkSections(sections)
        )
    }
}
