package com.novelfa.reader.parser

import com.novelfa.reader.model.Chapter
import com.novelfa.reader.model.ParsedBook
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {
    private const val OPF_NS = "http://www.idpf.org/2007/opf"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

    fun parse(extractDir: File, epubFileName: String): ParsedBook {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val opfFile = findOpfFile(dbf, extractDir)
            ?: throw IllegalArgumentException("No OPF package document found in EPUB")
        val opfFolder = opfFile.parentFile!!
        val odoc = dbf.newDocumentBuilder().parse(opfFile)
        val titleNodes = elements(odoc, DC_NS, "title")
        val title =
            if (titleNodes.length > 0 && titleNodes.item(0).textContent.isNotBlank()) {
                titleNodes.item(0).textContent.trim()
            } else {
                File(epubFileName).nameWithoutExtension
            }
        val manifest = mutableMapOf<String, Pair<String, String?>>()
        val items = elements(odoc, OPF_NS, "item")
        for (i in 0 until items.length) {
            val el = items.item(i)
            val id = el.attributes.getNamedItem("id")?.nodeValue?.trim() ?: continue
            val href = el.attributes.getNamedItem("href")?.nodeValue?.trim() ?: continue
            if (id.isEmpty() || href.isEmpty()) continue
            if (manifest.containsKey(id)) continue
            manifest[id] = href to el.attributes.getNamedItem("media-type")?.nodeValue
        }
        var coverHref: String? = null
        val metas = elements(odoc, OPF_NS, "meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i)
            if (el.attributes.getNamedItem("name")?.nodeValue == "cover") {
                val cid = el.attributes.getNamedItem("content")?.nodeValue
                if (cid != null && manifest.containsKey(cid)) {
                    coverHref = manifest[cid]!!.first
                    break
                }
            }
        }
        if (coverHref == null) {
            coverHref = manifest.values.firstOrNull { (h, t) ->
                t != null && t.startsWith("image") && h.lowercase().contains("cover")
            }?.first
        }
        if (coverHref == null) {
            coverHref = manifest.values.firstOrNull { (_, t) ->
                t != null && t.startsWith("image")
            }?.first
        }
        val coverFile = coverHref?.let { HrefResolver.resolve(opfFolder, it).takeIf { f -> f.exists() } }
        val spine = elements(odoc, OPF_NS, "itemref")
        val chapters = mutableListOf<Chapter>()
        var idx = 1
        for (i in 0 until spine.length) {
            val idref = spine.item(i).attributes.getNamedItem("idref")?.nodeValue?.trim() ?: continue
            if (idref.isEmpty()) continue
            val href = manifest[idref]?.first ?: continue
            chapters += Chapter(title = "فصل $idx", file = HrefResolver.resolve(opfFolder, href))
            idx++
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = coverFile)
    }

    /**
     * Namespace-aware lookup with a plain-tag fallback: real-world books
     * declare namespaces inconsistently (missing default ns, odd prefixes),
     * and a strict NS query silently returns zero nodes (blank library).
     */
    private fun elements(
        doc: org.w3c.dom.Document,
        ns: String,
        local: String
    ): org.w3c.dom.NodeList {
        val strict = doc.getElementsByTagNameNS(ns, local)
        if (strict.length > 0) return strict
        return doc.getElementsByTagName(local)
    }

    /**
     * Locates the OPF package document: standard container.xml first, then a
     * recursive *.opf scan of the extracted tree (tolerates missing container,
     * odd casing and nested layouts).
     */
    private fun findOpfFile(
        dbf: DocumentBuilderFactory,
        extractDir: File
    ): File? {
        val container = File(extractDir, "META-INF" + File.separatorChar + "container.xml")
        if (container.isFile) {
            val found = runCatching {
                val cdoc = dbf.newDocumentBuilder().parse(container)
                val rootfiles = elements(cdoc, CONTAINER_NS, "rootfile")
                if (rootfiles.length == 0) return@runCatching null
                val rel = rootfiles.item(0).attributes.getNamedItem("full-path")
                    ?.nodeValue?.trim() ?: return@runCatching null
                File(extractDir, rel.replace('/', File.separatorChar)).takeIf { it.isFile }
            }.getOrNull()
            if (found != null) return found
        }
        return extractDir.walkTopDown()
            .firstOrNull { it.isFile && it.extension.equals("opf", ignoreCase = true) }
    }
}
