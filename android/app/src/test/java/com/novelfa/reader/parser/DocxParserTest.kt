package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    private fun buildDocx(
        documentXml: String,
        coreXml: String? = null,
        relsXml: String? = null,
        numberingXml: String? = null,
        media: Map<String, ByteArray> = emptyMap()
    ): File {
        val f = tempFolder.newFile("book.docx")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun entry(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
            entry("[Content_Types].xml", "<Types/>".toByteArray())
            entry("word/document.xml", documentXml.toByteArray(Charsets.UTF_8))
            if (coreXml != null) entry("docProps/core.xml", coreXml.toByteArray(Charsets.UTF_8))
            if (relsXml != null) entry("word/_rels/document.xml.rels", relsXml.toByteArray(Charsets.UTF_8))
            if (numberingXml != null) entry("word/numbering.xml", numberingXml.toByteArray(Charsets.UTF_8))
            for ((name, bytes) in media) entry("word/media/$name", bytes)
        }
        return f
    }

    private val ns =
        "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun para(text: String, style: String? = null, bold: Boolean = false): String {
        val pPr = if (style != null) {
            "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>"
        } else ""
        val b = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        return "<w:p>$pPr<w:r>$b<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
    }

    @Test
    fun headings_splitSections_boldSurvives_coreTitleWins() {
        val doc = "<w:document $ns><w:body>" +
            para("فصل اول", "Heading1") +
            para("سلام دنیا", bold = true) +
            para("فصل دوم", "Heading1") +
            para("ادامه") +
            "</w:body></w:document>"
        val core = "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>کتاب من</dc:title></cp:coreProperties>"
        val docx = buildDocx(doc, coreXml = core)
        val out = tempFolder.newFolder("out")
        val book = DocxParser.parseFile(docx, out, ::fallback)
        assertEquals("کتاب من", book.title)
        assertEquals(listOf("فصل اول", "فصل دوم"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].file.readText().contains("<strong>سلام دنیا</strong>"))
    }

    @Test
    fun image_firstMediaBecomesCover_chapterReferencesIt() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val drawing = "<w:p><w:r><w:drawing><a:blip xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "r:embed=\"rId5\"/></w:drawing></w:r></w:p>"
        val doc = "<w:document $ns><w:body>" +
            para("شروع") + drawing + "</w:body></w:document>"
        val rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
            "Target=\"media/pic.png\"/></Relationships>"
        val docx = buildDocx(doc, relsXml = rels, media = mapOf("pic.png" to png))
        val out = tempFolder.newFolder("out")
        val book = DocxParser.parseFile(docx, out, ::fallback)
        assertTrue(book.coverFile != null && book.coverFile!!.exists())
        val body = book.chapters.single().file.readText()
        assertTrue(body.contains("<img src=\"media/pic.png\""))
    }

    @Test
    fun listParagraphs_bulletAndNumbered() {
        val numbering = "<w:numbering $ns>" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num></w:numbering>"
        fun listPara(text: String, numId: Int): String =
            "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr></w:pPr>" +
                "<w:r><w:t>$text</w:t></w:r></w:p>"
        val doc = "<w:document $ns><w:body>" +
            listPara("گلوله یک", 1) + listPara("گلوله دو", 1) +
            listPara("شماره یک", 2) + "</w:body></w:document>"
        val docx = buildDocx(doc, numberingXml = numbering)
        val out = tempFolder.newFolder("out")
        val book = DocxParser.parseFile(docx, out, ::fallback)
        val body = book.chapters.single().file.readText()
        assertTrue(body.contains("<ul>") && body.contains("<li>گلوله یک</li>"))
        assertTrue(body.contains("<ol>") && body.contains("<li>شماره یک</li>"))
    }

    @Test
    fun missingDocumentXml_throws() {
        val f = tempFolder.newFile("empty.docx")
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("<Types/>".toByteArray())
            zos.closeEntry()
        }
        val out = tempFolder.newFolder("out")
        try {
            DocxParser.parseFile(f, out, ::fallback)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("document.xml"))
        }
    }
}
