package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.EpubParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The EPUB engine. Its import half is the single biggest import win
 * in this change: previously import unzipped the WHOLE book and
 * parsed every spine item's title just to fill the library row. Now
 * import reads exactly two small XML entries out of the ZIP —
 * container.xml and the OPF package document — plus, at most, the
 * single cover image entry. No XHTML is touched, no font or image
 * entry is extracted, and the extracted tree is built only at first
 * open, where the reader already unzipped on a cache miss.
 *
 * The open half is unchanged behaviour: extract, then [EpubParser]
 * over the extracted tree, so spine order, TOC, cover and images
 * render exactly as before.
 */
object EpubEngine : BookEngine {
    override val format: BookFormat = BookFormat.EPUB

    /** A cover larger than this is not extracted at import; the
     *  generated cover fills in instead. Covers are small; a huge
     *  "cover" entry is almost always a mislabelled page image. */
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
            val opfPath = findOpfPath(zf) ?: throw IllegalArgumentException(
                "No OPF package document found in EPUB"
            )
            val opfBytes = zf.getInputStream(zf.getEntry(opfPath)).use { it.readBytes() }
            val opf = parseXml(opfBytes)
            val title = firstText(opf, "title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val spineCount = elements(opf, "itemref").length
            if (spineCount <= 0) throw IllegalArgumentException(
                "EPUB has no spine items: $displayName"
            )
            val coverFile = extractCover(zf, opf, opfPath, scratchDir)
            return EngineMetadata(title, spineCount, coverFile)
        }
    }

    override suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        ZipExtract.extractAll(source, outDir, cancelCheck)
        return EpubParser.parse(
            outDir,
            displayName?.takeIf { it.isNotBlank() } ?: source.name,
            chapterFallback,
            cancelCheck
        )
    }

    private fun findOpfPath(zf: ZipFile): String? {
        val container = zf.getEntry("META-INF/container.xml") ?: return zf.entries().asSequence()
            .firstOrNull { !it.isDirectory && it.name.endsWith(".opf", ignoreCase = true) }
            ?.name
        val bytes = zf.getInputStream(container).use { it.readBytes() }
        val doc = runCatching { parseXml(bytes) }.getOrNull() ?: return null
        val rootfiles = elements(doc, "rootfile")
        for (i in 0 until rootfiles.length) {
            val path = (rootfiles.item(i) as? Element)?.getAttribute("full-path")
                ?.takeIf { it.isNotBlank() } ?: continue
            if (zf.getEntry(path) != null) return path
        }
        return zf.entries().asSequence()
            .firstOrNull { !it.isDirectory && it.name.endsWith(".opf", ignoreCase = true) }
            ?.name
    }

    private fun extractCover(
        zf: ZipFile,
        opf: Document,
        opfPath: String,
        scratchDir: File
    ): File? {
        val opfDir = opfPath.substringBeforeLast('/', "")
        val items = elements(opf, "item")
        // Same cover order as the full parser, so import-time
        // covers never change for an existing book:
        //  1. <meta name="cover" content="..."/> pointing at a
        //     manifest item id;
        //  2. an item whose properties declare "cover";
        //  3. the first image whose href mentions "cover";
        //  4. the first image at all.
        // Every step still reads only one image entry out of the
        // whole archive.
        val itemsById = mutableMapOf<String, String>()
        var firstImageHref: String? = null
        var coverNameHref: String? = null
        var declaredHref: String? = null
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            val href = el.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
            val mediaType = el.getAttribute("media-type")
            val properties = el.getAttribute("properties")
            val id = el.getAttribute("id")
            if (id.isNotBlank()) itemsById[id] = href
            if (mediaType.startsWith("image/")) {
                if (firstImageHref == null) firstImageHref = href
                if (coverNameHref == null && "cover" in href.lowercase()) coverNameHref = href
                if (declaredHref == null && "cover" in properties.split(' ')) declaredHref = href
            }
        }
        var coverHref: String? = null
        val metas = elements(opf, "meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i) as? Element ?: continue
            if (el.getAttribute("name") == "cover") {
                coverHref = itemsById[el.getAttribute("content")] ?: continue
                break
            }
        }
        val href = coverHref ?: declaredHref ?: coverNameHref ?: firstImageHref ?: return null
        val entryName = if (opfDir.isEmpty()) href else "$opfDir/$href"
        val entry = zf.getEntry(entryName) ?: return null
        if (entry.size > MAX_COVER_BYTES) return null
        scratchDir.mkdirs()
        val dest = File(scratchDir, "cover." + entryName.substringAfterLast('.', "jpg"))
        zf.getInputStream(entry).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest.takeIf { it.exists() && it.length() > 0 }
    }

    private fun parseXml(bytes: ByteArray): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        return dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun elements(doc: Document, localName: String): org.w3c.dom.NodeList {
        val strict = doc.getElementsByTagNameNS("*", localName)
        return if (strict.length > 0) strict else doc.getElementsByTagName(localName)
    }

    private fun firstText(doc: Document, localName: String): String? {
        val nodes = elements(doc, localName)
        for (i in 0 until nodes.length) {
            val text = nodes.item(i)?.textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }
}
