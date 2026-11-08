package com.novelfa.reader.parser.engine

import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.TextBookParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun zipFile(name: String, entries: Map<String, ByteArray>): File {
        val f = tempFolder.newFile(name)
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((entryName, bytes) in entries) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return f
    }

    // --- Registry: one engine per format, no shared dispatch ---

    @Test
    fun registry_mapsEveryFormatToItsOwnEngine() {
        assertTrue(EngineRegistry.engineFor(BookFormat.EPUB) === EpubEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.DOCX) === DocxEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.PDF) === PdfEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.CBZ) === CbzEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.ZIP) === ZipEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.FB2) === Fb2Engine)
        // FBZ is the zipped FB2; it shares the FictionBook engine.
        assertTrue(EngineRegistry.engineFor(BookFormat.FBZ) === Fb2Engine)
        assertTrue(EngineRegistry.engineFor(BookFormat.RTF) === RtfEngine)
        // CBR and RAR are the same RAR comic under two extensions;
        // both share the RAR engine.
        assertTrue(EngineRegistry.engineFor(BookFormat.CBR) === RarEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.RAR) === RarEngine)
        // TXT and Markdown share the text engine, by design.
        assertTrue(EngineRegistry.engineFor(BookFormat.TXT) === TxtEngine)
        assertTrue(EngineRegistry.engineFor(BookFormat.MARKDOWN) === TxtEngine)
    }

    // --- ZIP classification: book wins over images, multi needs choice ---

    @Test
    fun zip_imageOnly_isComicWithNaturalPageCount() {
        val zip = zipFile(
            "comic.zip",
            mapOf(
                "page-10.jpg" to byteArrayOf(1),
                "__MACOSX/junk.jpg" to byteArrayOf(2),
                "page-2.jpg" to byteArrayOf(3)
            )
        )
        val kind = ZipEngine.classify(zip)
        assertTrue(kind is ZipKind.ImageOnly)
        assertEquals(2, (kind as ZipKind.ImageOnly).pageCount)
    }

    @Test
    fun zip_singleBook_winsOverLooseImages() {
        val zip = zipFile(
            "mixed.zip",
            mapOf(
                "book.epub" to "PK".toByteArray(),
                "cover.jpg" to byteArrayOf(1)
            )
        )
        val kind = ZipEngine.classify(zip)
        assertTrue(kind is ZipKind.SingleBook)
        assertEquals(BookFormat.EPUB, (kind as ZipKind.SingleBook).candidate.format)
    }

    @Test
    fun zip_fb2AndRtf_areBookCandidates() {
        val fb2 = zipFile(
            "fb2.zip",
            mapOf("novel.fb2" to "<FictionBook/>".toByteArray())
        )
        val fb2Kind = ZipEngine.classify(fb2)
        assertTrue(fb2Kind is ZipKind.SingleBook)
        assertEquals(BookFormat.FB2, (fb2Kind as ZipKind.SingleBook).candidate.format)
        val rtf = zipFile(
            "rtf.zip",
            mapOf("doc.rtf" to "{\\rtf1 hello}".toByteArray())
        )
        val rtfKind = ZipEngine.classify(rtf)
        assertTrue(rtfKind is ZipKind.SingleBook)
        assertEquals(BookFormat.RTF, (rtfKind as ZipKind.SingleBook).candidate.format)
    }

    @Test
    fun zip_multiBook_listsCandidatesInsteadOfGuessing() {
        val zip = zipFile(
            "multi.zip",
            mapOf(
                "a.epub" to "PK".toByteArray(),
                "sub/b.docx" to "PK".toByteArray()
            )
        )
        val kind = ZipEngine.classify(zip)
        assertTrue(kind is ZipKind.MultiBook)
        val candidates = (kind as ZipKind.MultiBook).candidates
        assertEquals(listOf("a.epub", "b.docx"), candidates.map { it.displayName })
    }

    @Test
    fun zip_nothingReadable_isReportedAsSuch() {
        val zip = zipFile("empty.zip", mapOf("notes.txt" to "hi".toByteArray()))
        // A lone .txt inside a zip IS a book candidate, so use a
        // genuinely unreadable entry instead.
        val zip2 = zipFile("junk.zip", mapOf("data.bin" to byteArrayOf(1, 2, 3)))
        assertTrue(ZipEngine.classify(zip2) is ZipKind.NothingReadable)
        assertTrue(ZipEngine.classify(zip) is ZipKind.SingleBook)
    }

    @Test
    fun zip_openCandidate_delegatesToTheBooksOwnEngine() = runBlocking {
        val zip = zipFile(
            "book.zip",
            mapOf("hello.txt" to "Hello\n\nWorld\n".toByteArray(Charsets.UTF_8))
        )
        val kind = ZipEngine.classify(zip) as ZipKind.SingleBook
        val outDir = tempFolder.newFolder("open")
        val book = ZipEngine.openCandidate(
            zip, kind.candidate, outDir, { n -> "Section $n" }, "hello.txt", null, null
        )
        assertTrue(book.chapters.isNotEmpty())
    }

    // --- EPUB metadata: container + OPF only, no full unzip ---

    @Test
    fun epub_metadata_readsTitleSpineAndCoverWithoutUnzipping() {
        val src = File("src/test/resources/book1")
        val entries = mutableMapOf<String, ByteArray>()
        src.walkTopDown().filter { it.isFile }.forEach { file ->
            entries[file.relativeTo(src).path.replace(File.separatorChar, '/')] = file.readBytes()
        }
        val epub = zipFile("book.epub", entries)
        val scratch = tempFolder.newFolder("scratch")
        val meta = EpubEngine.metadata(epub, "book.epub", scratch, null)
        assertEquals("My Title", meta.title)
        assertEquals(2, meta.chapterCount)
        assertTrue(meta.coverFile?.exists() == true)
        // The scratch dir holds at most the cover: no XHTML was
        // extracted at import.
        assertEquals(
            listOf(meta.coverFile!!.name),
            scratch.listFiles()!!.map { it.name }
        )
    }

    // --- DOCX metadata: core title + heading scan, no media unzip ---

    @Test
    fun docx_metadata_countsHeadingSectionsWithoutParsingBody() {
        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        fun para(text: String, style: String? = null): String {
            val pPr = if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else ""
            return "<w:p>$pPr<w:r><w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
        }
        val documentXml = "<w:document $ns><w:body>" +
            para("Intro") +
            para("Part one", "Heading1") +
            para("Body") +
            para("Part two", "Heading1") +
            "</w:body></w:document>"
        val coreXml = "<?xml version=\"1.0\"?><cp:coreProperties " +
            "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>Doc Title</dc:title></cp:coreProperties>"
        val docx = zipFile(
            "book.docx",
            mapOf(
                "word/document.xml" to documentXml.toByteArray(Charsets.UTF_8),
                "docProps/core.xml" to coreXml.toByteArray(Charsets.UTF_8),
                "word/media/big.png" to ByteArray(1024)
            )
        )
        val meta = DocxEngine.metadata(docx, "book.docx", tempFolder.newFolder("scratch2"), null)
        assertEquals("Doc Title", meta.title)
        // Intro before the first heading + two heading sections.
        assertEquals(3, meta.chapterCount)
    }

    @Test
    fun docx_cover_isFirstBodyReferencedImageNotFirstMediaEntry() {
        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val documentXml = "<w:document $ns><w:body>" +
            "<w:p><w:r><w:drawing><a:blip r:embed=\"rId2\"/></w:drawing></w:r></w:p>" +
            "<w:p><w:r><w:drawing><a:blip r:embed=\"rId1\"/></w:drawing></w:r></w:p>" +
            "<w:p><w:r><w:t>Text</w:t></w:r></w:p>" +
            "</w:body></w:document>"
        val relsXml = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/a.png\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/b.png\"/>" +
            "</Relationships>"
        val docx = zipFile(
            "cover.docx",
            mapOf(
                "word/document.xml" to documentXml.toByteArray(Charsets.UTF_8),
                "word/_rels/document.xml.rels" to relsXml.toByteArray(Charsets.UTF_8),
                "word/media/a.png" to "AAA".toByteArray(),
                "word/media/b.png" to "BBB".toByteArray()
            )
        )
        val meta = DocxEngine.metadata(docx, "cover.docx", tempFolder.newFolder("scratch3"), null)
        assertEquals("BBB", meta.coverFile!!.readText())
    }

    // --- FB2 metadata: title + section count + cover, no DOM parse ---

    private val tinyPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private fun sampleFb2File(name: String): File {
        val f = tempFolder.newFile(name)
        f.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" " +
                "xmlns:l=\"http://www.w3.org/1999/xlink\">\n" +
                "<description><title-info><book-title>عنوان کتاب</book-title>" +
                "<coverpage><image l:href=\"#cover\"/></coverpage>" +
                "</title-info></description>\n" +
                "<body><section><title><p>یک</p></title><p>متن</p>" +
                "<section><title><p>تو در تو</p></title><p>زیر</p></section>" +
                "</section>" +
                "<section><title><p>دو</p></title><p>متن</p></section></body>\n" +
                "<binary id=\"cover\" content-type=\"image/png\">$tinyPngBase64</binary>\n" +
                "</FictionBook>\n",
            Charsets.UTF_8
        )
        return f
    }

    @Test
    fun fb2_metadata_readsTitleSectionCountAndCoverWithoutDom() {
        val fb2 = sampleFb2File("book.fb2")
        val scratch = tempFolder.newFolder("scratch-fb2")
        val meta = Fb2Engine.metadata(fb2, "book.fb2", scratch, null)
        assertEquals("عنوان کتاب", meta.title)
        // Two top-level sections; the nested one is NOT counted.
        assertEquals(2, meta.chapterCount)
        assertTrue(meta.coverFile?.exists() == true)
        // The scratch dir holds at most the cover: no chapter files.
        assertEquals(
            listOf(meta.coverFile!!.name),
            scratch.listFiles()!!.map { it.name }
        )
    }

    @Test
    fun fb2_metadata_fallsBackToFileName() {
        val f = tempFolder.newFile("noname.fb2")
        f.writeText(
            "<?xml version=\"1.0\"?><FictionBook>" +
                "<description><title-info></title-info></description>" +
                "<body><section><p>x</p></section></body></FictionBook>",
            Charsets.UTF_8
        )
        val meta = Fb2Engine.metadata(f, "noname.fb2", tempFolder.newFolder("scratch-fb2b"), null)
        assertEquals("noname", meta.title)
        assertEquals(1, meta.chapterCount)
        assertTrue(meta.coverFile == null)
    }

    @Test
    fun fb2_open_parsesChaptersThroughEngine() = runBlocking {
        val fb2 = sampleFb2File("open.fb2")
        val outDir = tempFolder.newFolder("open-fb2")
        val book = Fb2Engine.open(fb2, outDir, { n -> "Section $n" }, "open.fb2", null, null)
        assertEquals("عنوان کتاب", book.title)
        assertEquals(2, book.chapters.size)
        assertTrue(book.coverFile?.exists() == true)
    }

    @Test
    fun fbz_open_unwrapsZipEntryThroughEngine() = runBlocking {
        val fbz = zipFile(
            "book.fbz",
            mapOf("book.fb2" to sampleFb2Bytes())
        )
        val outDir = tempFolder.newFolder("open-fbz")
        val book = Fb2Engine.open(fbz, outDir, { n -> "Section $n" }, "book.fbz", null, null)
        assertEquals("عنوان کتاب", book.title)
        assertEquals(2, book.chapters.size)
    }

    private fun sampleFb2Bytes(): ByteArray {
        val f = sampleFb2File("tmp.fb2")
        return f.readBytes()
    }

    // --- RTF: parse at import (the TXT rule), title from {\info} ---

    @Test
    fun rtf_open_parsesThroughEngine() = runBlocking {
        val rtf = tempFolder.newFile("doc.rtf")
        rtf.writeText(
            "{\\rtf1\\ansi{\\info{\\title RTF Title}}" +
                "\\pard Hello \\b bold\\b0.\\par\\sect Second.\\par}",
            Charsets.ISO_8859_1
        )
        val outDir = tempFolder.newFolder("open-rtf")
        val book = RtfEngine.open(rtf, outDir, { n -> "Section $n" }, "doc.rtf", null, null)
        assertEquals("RTF Title", book.title)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].file.readText().contains("<strong>bold</strong>"))
    }

    @Test
    fun rtf_metadata_titleOnly() {
        val rtf = tempFolder.newFile("meta.rtf")
        rtf.writeText(
            "{\\rtf1\\ansi{\\info{\\title RTF Title}}Hello\\par}",
            Charsets.ISO_8859_1
        )
        val meta = RtfEngine.metadata(rtf, "meta.rtf", tempFolder.newFolder("scratch-rtf"), null)
        assertEquals("RTF Title", meta.title)
        // Unknown until the parse: null, never zero.
        assertEquals(null, meta.chapterCount)
        assertEquals(null, meta.coverFile)
    }

    // --- Text decoding: Persian 1256 wins only on Arabic evidence ---

    @Test
    fun txt_windows1256Persian_decodesAsPersian() {
        // "سلام" only: Farsi yeh (U+06CC, as in دنیا) has no
        // windows-1256 mapping at all, so encoding it would already
        // substitute '?' before decodeText ever runs — that would
        // test the test's encoder, not the decoder.
        val persian = "سلام"
        val bytes = persian.toByteArray(charset("windows-1256"))
        assertEquals(persian, TextBookParser.decodeText(bytes))
    }

    @Test
    fun txt_latinSmartQuotes_doNotBecomeArabic() {
        val bytes = byteArrayOf(
            'H'.code.toByte(), 'i'.code.toByte(), ' '.code.toByte(),
            0x93.toByte(), 'x'.code.toByte(), 0x94.toByte()
        )
        val decoded = TextBookParser.decodeText(bytes)
        assertTrue(!decoded.any { it in '\u0600'..'\u06FF' })
        assertTrue(decoded.contains('x'))
    }
}
