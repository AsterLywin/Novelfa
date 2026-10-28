package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    /** Builds a classic-xref PDF from object bodies (dict + stream bytes). */
    private fun buildPdf(
        objects: Map<Int, ByteArray>,
        trailerExtra: String = ""
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = mutableMapOf<Int, Int>()
        for ((num, body) in objects.toSortedMap()) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xrefPos = out.size()
        val max = objects.keys.max()
        out.write("xref\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0 ${max + 1}\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (n in 1..max) {
            val off = offsets[n] ?: 0
            out.write(
                "%010d 00000 n \n".format(java.util.Locale.US, off)
                    .toByteArray(Charsets.ISO_8859_1)
            )
        }
        out.write(
            "trailer\n<< /Size ${max + 1} /Root 1 0 R $trailerExtra >>\nstartxref\n$xrefPos\n%%EOF\n"
                .toByteArray(Charsets.ISO_8859_1)
        )
        return out.toByteArray()
    }

    private fun streamObj(dict: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(dict.toByteArray(Charsets.ISO_8859_1))
        out.write("\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(content)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun flate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun twoPagePdf(
        page1Content: ByteArray,
        page1Dict: String = "<< /Length ${page1Content.size} >>",
        page2Content: ByteArray = "BT /F1 12 Tf (Page two) Tj ET".toByteArray(Charsets.ISO_8859_1),
        outline: Boolean = true
    ): ByteArray {
        val objects = mutableMapOf<Int, ByteArray>()
        objects[1] = ("<< /Type /Catalog /Pages 2 0 R" +
            (if (outline) " /Outlines 8 0 R" else "") + " >>")
            .toByteArray(Charsets.ISO_8859_1)
        objects[2] = "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"
            .toByteArray(Charsets.ISO_8859_1)
        objects[3] = ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>")
            .toByteArray(Charsets.ISO_8859_1)
        objects[4] = ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>")
            .toByteArray(Charsets.ISO_8859_1)
        objects[5] = ("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica " +
            "/Encoding /WinAnsiEncoding >>").toByteArray(Charsets.ISO_8859_1)
        objects[6] = streamObj(page1Dict, page1Content)
        objects[7] = streamObj("<< /Length ${page2Content.size} >>", page2Content)
        if (outline) {
            objects[8] = "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>"
                .toByteArray(Charsets.ISO_8859_1)
            objects[9] = ("<< /Title (Chapter One) /Parent 8 0 R /Next 10 0 R " +
                "/Dest [3 0 R /XYZ 0 700 0] >>").toByteArray(Charsets.ISO_8859_1)
            objects[10] = ("<< /Title (Chapter Two) /Parent 8 0 R /Prev 9 0 R " +
                "/Dest [4 0 R /XYZ 0 700 0] >>").toByteArray(Charsets.ISO_8859_1)
        }
        return buildPdf(objects)
    }

    @Test
    fun simplePdf_outlineChapters_textExtracted() {
        val content = "BT /F1 12 Tf 100 700 Td (Hello World) Tj T* (Second line) Tj ET"
            .toByteArray(Charsets.ISO_8859_1)
        val pdf = twoPagePdf(content)
        val out = tempFolder.newFolder("out")
        val book = PdfParser.parse(pdf, "book.pdf", out, ::fallback)
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.title })
        val first = book.chapters[0].file.readText()
        assertTrue(first.contains("Hello World"))
        assertTrue(first.contains("Second line"))
        assertTrue(book.chapters[1].file.readText().contains("Page two"))
    }

    @Test
    fun flateContent_decodes() {
        val raw = "BT /F1 12 Tf (Compressed text here) Tj ET"
            .toByteArray(Charsets.ISO_8859_1)
        val compressed = flate(raw)
        val pdf = twoPagePdf(
            compressed,
            page1Dict = "<< /Length ${compressed.size} /Filter /FlateDecode >>",
            outline = false
        )
        val out = tempFolder.newFolder("out")
        val book = PdfParser.parse(pdf, "book.pdf", out, ::fallback)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(body.contains("Compressed text here"))
    }

    @Test
    fun tjArray_kerningNumbers_becomeWordSpaces() {
        // TJ with a large negative number between words = a word gap.
        val content = ("BT /F1 12 Tf [(Hel) -300 (lo) ( World)] TJ ET")
            .toByteArray(Charsets.ISO_8859_1)
        val pdf = twoPagePdf(content, outline = false)
        val out = tempFolder.newFolder("out")
        val book = PdfParser.parse(pdf, "book.pdf", out, ::fallback)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(body.contains("Hel lo World"))
    }

    @Test(timeout = 15000)
    fun tjArray_strayNameAndComment_terminatesAndExtracts() {
        // Regression: a name or comment inside a [...] array used to
        // yield an empty word that never advanced the tokenizer, so
        // the whole PDF import hung forever behind the import dialog.
        val content = ("BT /F1 12 Tf [(Hel) /SomeName % a comment\n (lo)] TJ ET")
            .toByteArray(Charsets.ISO_8859_1)
        val pdf = twoPagePdf(content, outline = false)
        val out = tempFolder.newFolder("out")
        val book = PdfParser.parse(pdf, "book.pdf", out, ::fallback)
        val body = book.chapters.joinToString("") { it.file.readText() }
        assertTrue(body.contains("Hello"))
    }

    @Test
    fun halfBrokenPdf_badPage_doesNotKillBook() {
        // One page whose content object does not exist at all: the
        // book must still import with the good page's text, and the
        // bad page must read as text-less (the mixed path renders
        // such pages as images) instead of failing the whole parse.
        val good = "BT /F1 12 Tf (Good page) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val pdf = buildPdf(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>".toByteArray(Charsets.ISO_8859_1),
                3 to ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>").toByteArray(Charsets.ISO_8859_1),
                4 to ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 5 0 R >> >> /Contents 99 0 R >>").toByteArray(Charsets.ISO_8859_1),
                5 to ("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica " +
                    "/Encoding /WinAnsiEncoding >>").toByteArray(Charsets.ISO_8859_1),
                6 to streamObj("<< /Length ${good.size} >>", good)
            )
        )
        val out = tempFolder.newFolder("out")
        val (parsed, hasText, pageTexts) = PdfParser.parseDetailed(pdf, "book.pdf", out, ::fallback)
        assertEquals(listOf(true, false), hasText)
        assertTrue(pageTexts[0].joinToString("").contains("Good page"))
        assertTrue(pageTexts[1].isEmpty())
        assertTrue(parsed.chapters.isNotEmpty())
    }

    @Test
    fun encryptedPdf_danglingEncryptRef_unsupported() {
        // A trailer claiming /Encrypt whose dict object does not exist
        // is malformed encryption, not an unencrypted file.
        val content = "BT /F1 12 Tf (Secret) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val pdf = buildPdf(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1),
                3 to ("<< /Type /Page /Parent 2 0 R " +
                    "/Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>")
                    .toByteArray(Charsets.ISO_8859_1),
                5 to ("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica " +
                    "/Encoding /WinAnsiEncoding >>").toByteArray(Charsets.ISO_8859_1),
                6 to streamObj("<< /Length ${content.size} >>", content)
            ),
            trailerExtra = "/Encrypt 99 0 R"
        )
        val out = tempFolder.newFolder("out")
        try {
            PdfParser.parse(pdf, "book.pdf", out, ::fallback)
            throw AssertionError("expected unsupported-encryption rejection")
        } catch (e: PdfUnsupportedEncryptionException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun noOutline_chunksBySize() {
        val onePage = ("BT /F1 12 Tf (" + "word ".repeat(3000) + ") Tj ET")
            .toByteArray(Charsets.ISO_8859_1)
        val pdf = twoPagePdf(onePage, outline = false)
        val out = tempFolder.newFolder("out")
        val book = PdfParser.parse(pdf, "book.pdf", out, ::fallback)
        assertTrue(book.chapters.size > 1)
        assertTrue(book.chapters.all { it.title.startsWith("Section") })
    }

    // -- maybeEncrypted: the import fast path must never skip a real
    // decryption, and must say false for ordinary unencrypted files. --

    @Test
    fun maybeEncrypted_plainPdf_isFalse() {
        val pdf = twoPagePdf(
            "BT /F1 12 Tf (Plain) Tj ET".toByteArray(Charsets.ISO_8859_1),
            outline = false
        )
        assertEquals(false, PdfParser.maybeEncrypted(pdf))
    }

    @Test
    fun maybeEncrypted_encryptTrailer_isTrue() {
        val pdf = buildPdf(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Pages /Kids [] /Count 0 >>".toByteArray(Charsets.ISO_8859_1)
            ),
            trailerExtra = "/Encrypt 9 0 R"
        )
        assertEquals(true, PdfParser.maybeEncrypted(pdf))
    }

    @Test
    fun maybeEncrypted_noStartxref_isTrue() {
        // Unreadable trailers must keep the full probe (old behaviour),
        // never a skipped decryption.
        assertEquals(true, PdfParser.maybeEncrypted("not a pdf at all".toByteArray()))
        assertEquals(true, PdfParser.maybeEncrypted(ByteArray(0)))
    }

    @Test
    fun maybeEncrypted_prevChain_followsToEncrypt() {
        // Appends a second (newest) trailer with /Prev pointing at the
        // first trailer: the probe must walk the chain, not stop at
        // the newest trailer alone.
        val first = twoPagePdf(
            "BT /F1 12 Tf (Plain) Tj ET".toByteArray(Charsets.ISO_8859_1),
            outline = false
        )
        val firstText = String(first, Charsets.ISO_8859_1)
        val firstXref = firstText.substringAfter("startxref\n")
            .substringBefore("\n").trim().toInt()
        val secondXref = first.size
        val second = (
            "xref\n0 1\n0000000000 65535 f \n" +
                "trailer\n<< /Size 3 /Root 1 0 R /Prev $firstXref >>\n" +
                "startxref\n$secondXref\n%%EOF\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertEquals(false, PdfParser.maybeEncrypted(first + second))
        // Same chain, but the OLD trailer declares /Encrypt: the walk
        // must still find it through /Prev.
        val firstEnc = buildPdf(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Pages /Kids [] /Count 0 >>".toByteArray(Charsets.ISO_8859_1)
            ),
            trailerExtra = "/Encrypt 9 0 R"
        )
        val firstEncText = String(firstEnc, Charsets.ISO_8859_1)
        val firstEncXref = firstEncText.substringAfter("startxref\n")
            .substringBefore("\n").trim().toInt()
        val secondXref2 = firstEnc.size
        val second2 = (
            "xref\n0 1\n0000000000 65535 f \n" +
                "trailer\n<< /Size 3 /Root 1 0 R /Prev $firstEncXref >>\n" +
                "startxref\n$secondXref2\n%%EOF\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertEquals(true, PdfParser.maybeEncrypted(firstEnc + second2))
    }
}
