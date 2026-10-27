package com.novelfa.reader.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.novelfa.reader.model.ParsedBook
import java.io.File
import java.util.Locale

/**
 * ComicScreen-style image fallback for PDFs with no extractable text
 * (scanned / image-only PDFs): the platform renderer turns each PDF
 * page into an image, and the book becomes a single image section.
 * Vertical mode then scrolls it like a webtoon; paged mode shows one
 * image per page (the pager's full-bleed image-page rule already
 * handles a page whose only content is one image).
 *
 * Only reached when the text extractor yields zero chapters (or throws
 * a non-password error the renderer might still survive); text PDFs
 * never take this path. Mirrors the DOCX shape — images in `media/`,
 * referenced relatively — so the reader's existing image pipeline and
 * the open-path cache apply unchanged, and a re-open re-renders only
 * when the source file itself changed (the open cache stamp).
 */
object PdfImageBook {

    /** Image rendering is bounded: beyond this the import fails cleanly. */
    const val MAX_IMAGE_PAGES = 1000
    const val RENDER_WIDTH = 1440
    const val JPEG_QUALITY = 85
    private const val MAX_RENDER_HEIGHT = 8192

    /** Pure: stable, Locale.US-padded image file name for a page index. */
    fun fileNameFor(index: Int): String =
        "page-%04d.jpg".format(Locale.US, index + 1)

    /**
     * Pure: one section holding every page image, titled with the book
     * title (no new UI string needed — the drawer shows the book's own
     * title as its single section). Each image sits in its own <p> so
     * the pager's image-page rule can claim it as a full-bleed page.
     */
    fun buildSections(title: String, pageCount: Int): List<ConvertedBook.Section> {
        val body = (0 until pageCount).joinToString("") { i ->
            "<p><img src=\"media/" + fileNameFor(i) + "\"></p>"
        }
        return listOf(ConvertedBook.Section(title, body))
    }

    /**
     * Renders every page of [pdfFile] into `outDir/media/` and returns
     * the image book. The file must already be decrypted (the importer
     * stores password PDFs decrypted; the platform renderer cannot open
     * encrypted files at all).
     */
    fun render(pdfFile: File, outDir: File, title: String): ParsedBook {
        val count = renderPages(pdfFile, outDir, null)
        return ConvertedBook.write(outDir, title, buildSections(title, count))
    }

    /**
     * Renders [indices] (or every page when null) into `outDir/media/`
     * and returns the PDF's page count. Used by the mixed text+image
     * path, which renders only the image pages instead of the whole
     * book. The per-page cap still applies to a full render; a mixed
     * render is capped by its caller on the image-page count.
     */
    fun renderPages(pdfFile: File, outDir: File, indices: List<Int>?): Int {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                if (count <= 0) throw IllegalArgumentException("PDF has no pages")
                if (indices == null && count > MAX_IMAGE_PAGES) {
                    throw IllegalArgumentException("PDF has too many pages for image rendering")
                }
                val wanted = indices ?: (0 until count).toList()
                if (wanted.any { it < 0 || it >= count }) {
                    throw IllegalArgumentException("PDF page index out of range")
                }
                val mediaDir = File(outDir, "media").apply { mkdirs() }
                for (i in wanted) {
                    renderer.openPage(i).use { page ->
                        var width = RENDER_WIDTH
                        var height = (page.height * (width.toFloat() / page.width.toFloat()))
                            .toInt().coerceAtLeast(1)
                        if (height > MAX_RENDER_HEIGHT) {
                            // Absurdly tall page: shrink to the cap,
                            // width following, aspect preserved.
                            val shrink = MAX_RENDER_HEIGHT.toFloat() / height.toFloat()
                            height = MAX_RENDER_HEIGHT
                            width = (width * shrink).toInt().coerceAtLeast(1)
                        }
                        val bitmap = Bitmap.createBitmap(
                            width, height, Bitmap.Config.ARGB_8888
                        )
                        try {
                            page.render(
                                bitmap, null, null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            File(mediaDir, fileNameFor(i)).outputStream().use { stream ->
                                bitmap.compress(
                                    Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream
                                )
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                return count
            }
        }
    }
}
