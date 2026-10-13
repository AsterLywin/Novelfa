package com.novelfa.reader.parser

import com.novelfa.reader.model.Chapter
import com.novelfa.reader.model.ParsedBook
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {
    fun parse(extractDir: File, epubFileName: String): ParsedBook {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val container = File(extractDir, "META-INF" + File.separatorChar + "container.xml")
        val cdoc = dbf.newDocumentBuilder().parse(container)
        val rootfile = cdoc.getElementsByTagNameNS(
            "urn:oasis:names:tc:opendocument:xmlns:container", "rootfile"
        ).item(0)
        val opfRel = rootfile.attributes.getNamedItem("full-path").nodeValue
        val opfFile = File(extractDir, opfRel.replace('/', File.separatorChar))
        val opfFolder = opfFile.parentFile!!
        val odoc = dbf.newDocumentBuilder().parse(opfFile)
        val titleNodes = odoc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title")
        val title =
            if (titleNodes.length > 0 && titleNodes.item(0).textContent.isNotBlank()) {
                titleNodes.item(0).textContent.trim()
            } else {
                File(epubFileName).nameWithoutExtension
            }
        val manifest = mutableMapOf<String, Pair<String, String?>>()
        val items = odoc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "item")
        for (i in 0 until items.length) {
            val el = items.item(i)
            val id = el.attributes.getNamedItem("id")?.nodeValue ?: continue
            val href = el.attributes.getNamedItem("href")?.nodeValue ?: continue
            if (manifest.containsKey(id)) continue
            manifest[id] = href to el.attributes.getNamedItem("media-type")?.nodeValue
        }
        var coverHref: String? = null
        val metas = odoc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "meta")
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
        val spine = odoc.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "itemref")
        val chapters = mutableListOf<Chapter>()
        var idx = 1
        for (i in 0 until spine.length) {
            val idref = spine.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
            val href = manifest[idref]?.first ?: continue
            chapters += Chapter(title = "فصل $idx", file = HrefResolver.resolve(opfFolder, href))
            idx++
        }
        return ParsedBook(title = title, chapters = chapters, coverFile = coverFile)
    }
}
