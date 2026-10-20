package com.novelfa.reader.parser

import com.novelfa.reader.model.Chapter
import com.novelfa.reader.model.ParsedBook
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {
    private const val OPF_NS = "http://www.idpf.org/2007/opf"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
    /** Package documents (container.xml/OPF) are kilobytes; anything larger is hostile. */
    private const val MAX_PACKAGE_XML_BYTES = 10L * 1024 * 1024

    /**
     * @param chapterFallback localized title for a chapter that has none
     * (called with the 1-based chapter number) — supplied by the caller,
     * which owns a Context.
     */
    fun parse(
        extractDir: File,
        epubFileName: String,
        chapterFallback: (Int) -> String
    ): ParsedBook {
        val dbf = secureDocumentBuilderFactory()
        val opfFile = findOpfFile(dbf, extractDir)
            ?: throw IllegalArgumentException("No OPF package document found in EPUB")
        val opfFolder = opfFile.parentFile!!
        val odoc = try {
            parseXml(dbf, opfFile)
        } catch (e: Throwable) {
            // parseXml rejects DOCTYPE, but a hostile OPF can still blow the
            // stack (deep nesting) or memory — both surface as Error, which
            // the callers' catch (e: Exception) would miss and crash the app.
            throw IllegalArgumentException("Could not parse EPUB package document: ${e.message}")
        }
        val titleNodes = elements(odoc, DC_NS, "title")
        val title =
            if (titleNodes.length > 0 && titleNodes.item(0).textContent.isNotBlank()) {
                titleNodes.item(0).textContent.trim()
            } else {
                File(epubFileName).nameWithoutExtension
            }
        val manifest = mutableMapOf<String, Pair<String, String?>>()
        val items = elements(odoc, OPF_NS, "item")
        var navHref: String? = null
        var ncxHref: String? = null
        for (i in 0 until items.length) {
            val el = items.item(i)
            val id = el.attributes.getNamedItem("id")?.nodeValue?.trim() ?: continue
            val href = el.attributes.getNamedItem("href")?.nodeValue?.trim() ?: continue
            if (id.isEmpty() || href.isEmpty()) continue
            if (manifest.containsKey(id)) continue
            val mediaType = el.attributes.getNamedItem("media-type")?.nodeValue
            manifest[id] = href to mediaType
            val props = el.attributes.getNamedItem("properties")?.nodeValue
            if (props != null && props.split(" ").contains("nav")) navHref = href
            if (mediaType == "application/x-dtbncx+xml") ncxHref = href
        }
        var coverHref: String? = null
        val metas = elements(odoc, OPF_NS, "meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i)
            if (el.attributes.getNamedItem("name")?.nodeValue == "cover") {
                val cid = el.attributes.getNamedItem("content")?.nodeValue
                // Only accept actual images here (the fallbacks below check
                // media-type too); a hostile OPF could otherwise point the
                // cover at an HTML/bin file.
                val item = if (cid != null && manifest.containsKey(cid)) manifest[cid] else null
                if (item != null && (item.second == null || item.second!!.startsWith("image"))) {
                    coverHref = item.first
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
        // Real chapter titles: NCX (EPUB2) or nav doc (EPUB3) table of
        // contents, keyed by chapter file's canonical path. Best-effort —
        // any failure just leaves the map empty and titles fall back to
        // the file's <title> / "بخش N".
        val tocTitles: Map<String, String> = runCatching {
            val tocFile = (ncxHref ?: navHref)?.let { HrefResolver.resolve(opfFolder, it) }
            if (tocFile == null || !tocFile.exists()) return@runCatching emptyMap<String, String>()
            if (ncxHref != null) parseNcxTitles(dbf, tocFile) else parseNavTitles(dbf, tocFile)
        }.getOrDefault(emptyMap())
        val spine = elements(odoc, OPF_NS, "itemref")
        val chapters = mutableListOf<Chapter>()
        val seenFiles = mutableSetOf<String>()
        var idx = 1
        for (i in 0 until spine.length) {
            val idref = spine.item(i).attributes.getNamedItem("idref")?.nodeValue?.trim() ?: continue
            if (idref.isEmpty()) continue
            val (href, mediaType) = manifest[idref] ?: continue
            // Skip non-XHTML spine items (cover-in-spine etc.): a missing
            // media-type stays lenient, but an explicit non-document type
            // would render as binary garbage.
            if (mediaType != null &&
                !mediaType.equals("application/xhtml+xml", ignoreCase = true) &&
                !mediaType.equals("text/html", ignoreCase = true)
            ) continue
            val file = HrefResolver.resolve(opfFolder, href)
            // Dedupe: duplicate itemrefs (or hrefs resolving to the same file)
            // would produce duplicate LazyColumn keys and crash the drawer.
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (!seenFiles.add(canonical)) continue
            val title = tocTitles[canonical]
                ?: readXhtmlTitle(dbf, file)
                ?: chapterFallback(idx)
            chapters += Chapter(title = title, file = file)
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
            runCatching { isNamespaceAware = true }
            setFeatureSafe("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafe("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafe("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafe("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            // NOTE: setXIncludeAware/setExpandEntityReferences ALWAYS throw
            // UnsupportedOperationException on Android's DocumentBuilderFactory
            // (spec "Unknown" version "0.0") — keep them guarded (cf. 2446ec44).
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
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
        // Package documents are kilobytes; cap before readBytes() so a hostile
        // file can't OOM the parse (the zip-level cap is far larger).
        if (file.length() > MAX_PACKAGE_XML_BYTES) {
            throw IllegalArgumentException("Package document too large: ${file.name}")
        }
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

    /**
     * Lenient XML parse for TOC documents and chapter files, which commonly
     * carry a DOCTYPE. DOCTYPE is allowed but external DTDs/entities stay
     * disabled (same hardening as [secureDocumentBuilderFactory]), so the
     * XXE risk stays closed. Null on any failure.
     */
    private fun parseXmlAllowDoctype(dbf: DocumentBuilderFactory, file: File): org.w3c.dom.Document? =
        runCatching {
            if (file.length() > MAX_PACKAGE_XML_BYTES) return@runCatching null
            val f2 = secureDocumentBuilderFactory()
            runCatching { f2.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
            f2.newDocumentBuilder().parse(file.inputStream())
        }.getOrNull()

    /**
     * EPUB2 NCX table of contents → chapter file canonical path → title.
     * Only each navPoint's *direct* navLabel/content are read, so nested
     * navPoints keep their own titles.
     */
    private fun parseNcxTitles(dbf: DocumentBuilderFactory, ncxFile: File): Map<String, String> {
        val doc = parseXmlAllowDoctype(dbf, ncxFile) ?: return emptyMap()
        val folder = ncxFile.parentFile ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val navPoints = doc.getElementsByTagName("navPoint")
        for (i in 0 until navPoints.length) {
            val np = navPoints.item(i)
            var label: String? = null
            var src: String? = null
            val kids = np.childNodes
            for (j in 0 until kids.length) {
                val c = kids.item(j)
                if (c.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                when (c.nodeName) {
                    "navLabel" -> {
                        val texts = (c as org.w3c.dom.Element).getElementsByTagName("text")
                        if (texts.length > 0) label = texts.item(0).textContent?.trim()
                    }
                    "content" -> src = c.attributes.getNamedItem("src")?.nodeValue
                }
            }
            if (label.isNullOrBlank() || src.isNullOrBlank()) continue
            val key = resolveTocHref(folder, src) ?: continue
            out.putIfAbsent(key, label.trim())
        }
        return out
    }

    /**
     * EPUB3 nav document (`<nav epub:type="toc">`) → chapter file canonical
     * path → link text.
     */
    private fun parseNavTitles(dbf: DocumentBuilderFactory, navFile: File): Map<String, String> {
        val doc = parseXmlAllowDoctype(dbf, navFile) ?: return emptyMap()
        val folder = navFile.parentFile ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val navs = doc.documentElement?.let { elementsAnyNs(it, "nav") }
            ?: return emptyMap()
        // Prefer the <nav epub:type="toc">; a lone <nav> with no epub:type
        // (sloppy but common) is treated as the TOC.
        var tocNav: org.w3c.dom.Element? = null
        for (i in 0 until navs.length) {
            val nav = navs.item(i) as? org.w3c.dom.Element ?: continue
            val epubType = nav.getAttribute("epub:type")
                .ifEmpty { nav.getAttributeNS("http://www.idpf.org/2007/ops", "type") }
                .split(" ")
            if (epubType.contains("toc")) {
                tocNav = nav
                break
            }
        }
        if (tocNav == null && navs.length == 1) {
            tocNav = navs.item(0) as? org.w3c.dom.Element
        }
        val nav = tocNav ?: return emptyMap()
        val links = elementsAnyNs(nav, "a")
        for (j in 0 until links.length) {
            val a = links.item(j) as? org.w3c.dom.Element ?: continue
            val href = a.getAttribute("href")
            val label = a.textContent?.trim()
            if (href.isBlank() || label.isNullOrBlank()) continue
            val key = resolveTocHref(folder, href) ?: continue
            out.putIfAbsent(key, label)
        }
        return out
    }

    /**
     * Namespace-agnostic descendant lookup, scoped to [parent]: matches docs
     * with a default namespace, a prefix, or none at all.
     */
    private fun elementsAnyNs(
        parent: org.w3c.dom.Element,
        local: String
    ): org.w3c.dom.NodeList {
        val ns = parent.getElementsByTagNameNS("*", local)
        if (ns.length > 0) return ns
        return parent.getElementsByTagName(local)
    }

    /** Resolves a TOC href to a chapter file's canonical path (null if bad). */
    private fun resolveTocHref(folder: File, href: String): String? = runCatching {
        val clean = href.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty()) return@runCatching null
        HrefResolver.resolve(folder, clean).canonicalPath
    }.getOrNull()

    /** Best-effort `<title>` of a chapter file; null when absent/unparseable. */
    private fun readXhtmlTitle(dbf: DocumentBuilderFactory, file: File): String? = runCatching {
        if (!file.exists()) return@runCatching null
        val doc = parseXmlAllowDoctype(dbf, file) ?: return@runCatching null
        val root = doc.documentElement ?: return@runCatching null
        val titles = elementsAnyNs(root, "title")
        if (titles.length == 0) return@runCatching null
        titles.item(0).textContent?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
