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
        val dbf = secureDocumentBuilderFactory()
        val opfFile = findOpfFile(dbf, extractDir)
            ?: throw IllegalArgumentException("No OPF package document found in EPUB")
        val opfFolder = opfFile.parentFile!!
        val odoc = parseXml(dbf, opfFile)
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
                val cdoc = parseXml(dbf, container)
                val rootfiles = elements(cdoc, CONTAINER_NS, "rootfile")
                if (rootfiles.length == 0) return@runCatching null
                val rel = rootfiles.item(0).attributes.getNamedItem("full-path")
                    ?.nodeValue?.trim() ?: return@runCatching null
                val candidate = File(extractDir, rel.replace('/', File.separatorChar))
                // Confine the OPF to the extraction dir (crafted full-path
                // values like ../../databases/reader.db must not escape).
                val canonicalDir = extractDir.canonicalPath
                val canonicalOpf = candidate.canonicalPath
                if (!canonicalOpf.startsWith(canonicalDir + File.separatorChar)) {
                    return@runCatching null
                }
                candidate.takeIf { it.isFile }
            }.getOrNull()
            if (found != null) return found
        }
        return extractDir.walkTopDown()
            .firstOrNull { it.isFile && it.extension.equals("opf", ignoreCase = true) }
    }

    /**
     * XXE-hardened factory, BEST-EFFORT: several Android devices' built-in
     * DocumentBuilderFactory do not honor Apache feature flags and throw
     * ParserConfigurationException on setFeature — which hard-failed EVERY
     * import on those devices (cf. aws-amplify/aws-sdk-android#3336).
     * Unsupported flags are therefore skipped; the manual DOCTYPE scan in
     * [parseXml] is the portable guarantee and works on every JAXP provider.
     */
    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeatureSafe("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafe("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafe("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafe("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun DocumentBuilderFactory.setFeatureSafe(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    /**
     * Parses an EPUB package XML file after rejecting DOCTYPE declarations.
     * Portable XXE/DTD protection: unlike setFeature flags, a literal scan
     * works on every Android JAXP provider. DOCTYPE can only appear before
     * the root element and container.xml/OPF are small, so the whole file is
     * scanned (null bytes stripped to also catch UTF-16-encoded files).
     * Note: a tiny minority of ancient OEB 1.2 books declare a DOCTYPE in
     * the OPF — those fail import with a clean user-facing error.
     */
    private fun parseXml(dbf: DocumentBuilderFactory, file: File): org.w3c.dom.Document {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes != null) {
            val text = bytes.toString(Charsets.UTF_8).replace("\u0000", "")
            if (text.contains("<!DOCTYPE", ignoreCase = true)) {
                throw IllegalArgumentException(
                    "DOCTYPE declarations are not allowed in EPUB package files: ${file.name}"
                )
            }
        }
        return dbf.newDocumentBuilder().parse(file)
    }
}
