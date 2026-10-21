package com.novelfa.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One book's reading record: where the reader stopped, whether the book is
 * finished, favorite and shelved where. Records travel inside .novelfabak
 * backups (`records.json`, format v2) and live on-device in a small store,
 * so a book that is (re-)imported later — after a restore, an app
 * reinstall, or a plain delete + re-import — resumes where it left off.
 *
 * Identity is layered (strongest first):
 *  1. [sha256] of the EPUB file bytes — the exact same file.
 *  2. [epubId] — the OPF `dc:identifier` — the same publication from
 *     another source, where the bytes may differ.
 * The file name / title are display data only, never match keys (renames).
 *
 * PRIVATE books never get records: a backup file is not encrypted and a
 * locked shelf's titles must not leak into it.
 */
data class ReadingRecord(
    val sha256: String = "",
    val epubId: String = "",
    val title: String = "",
    val fileName: String = "",
    val chapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val percent: Double = 0.0,
    val finished: Boolean = false,
    val favorite: Boolean = false,
    val collection: String? = null,
    /** Epoch millis of the last reading activity (0 when unknown). */
    val updatedAt: Long = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sha256", sha256)
        .put("epubId", epubId)
        .put("title", title)
        .put("fileName", fileName)
        .put("chapter", chapterIndex)
        .put("chapters", totalChapters)
        .put("percent", percent)
        .put("finished", finished)
        .put("favorite", favorite)
        .put("collection", collection ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)

    /** Same book as [other] when either strong key matches (both non-blank). */
    fun sameBookAs(other: ReadingRecord): Boolean =
        (sha256.isNotBlank() && sha256 == other.sha256) ||
            (epubId.isNotBlank() && epubId == other.epubId)

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** Lenient per-record parse; null when the record is unusable. */
        fun fromJson(o: JSONObject): ReadingRecord? {
            val sha = o.optString("sha256", "").lowercase()
                .takeIf { SHA256_HEX.matches(it) } ?: ""
            val epubId = o.optString("epubId", "").trim().take(256)
            if (sha.isBlank() && epubId.isBlank()) return null
            val chapters = o.optInt("chapters", 0).coerceIn(0, 100_000)
            val chapter = o.optInt("chapter", 0)
                .coerceIn(0, maxOf(0, chapters - 1).coerceAtLeast(0))
            return ReadingRecord(
                sha256 = sha,
                epubId = epubId,
                title = o.optString("title", "").take(512),
                fileName = o.optString("fileName", "").take(256),
                chapterIndex = chapter,
                totalChapters = chapters,
                percent = o.optDouble("percent", 0.0).coerceIn(0.0, 100.0),
                finished = o.optBoolean("finished", false),
                favorite = o.optBoolean("favorite", false),
                collection = if (o.isNull("collection")) null
                else o.optString("collection", "").take(128).ifBlank { null },
                updatedAt = o.optLong("updatedAt", 0).coerceAtLeast(0)
            )
        }

        /**
         * Merge two records of the same book: the further-along reading
         * state wins (finished beats unfinished, then higher percent);
         * favorite and finished are sticky; identity keys are unioned so
         * the merged record matches by either key later.
         */
        fun merge(a: ReadingRecord, b: ReadingRecord): ReadingRecord {
            val winner = when {
                a.finished != b.finished -> if (a.finished) a else b
                a.percent != b.percent -> if (a.percent > b.percent) a else b
                else -> if (a.updatedAt >= b.updatedAt) a else b
            }
            val loser = if (winner === a) b else a
            return winner.copy(
                sha256 = winner.sha256.ifBlank { loser.sha256 },
                epubId = winner.epubId.ifBlank { loser.epubId },
                title = winner.title.ifBlank { loser.title },
                fileName = winner.fileName.ifBlank { loser.fileName },
                finished = a.finished || b.finished,
                favorite = a.favorite || b.favorite,
                collection = winner.collection ?: loser.collection,
                updatedAt = maxOf(a.updatedAt, b.updatedAt)
            )
        }

        /** Serialize a record set (envelope versioned independently). */
        fun listToJson(records: List<ReadingRecord>): ByteArray {
            val root = JSONObject()
                .put("v", 1)
                .put("books", JSONArray().apply { records.forEach { put(it.toJson()) } })
            return root.toString().toByteArray(Charsets.UTF_8)
        }

        /** Lenient parse of the envelope; broken records are skipped. */
        fun listFromJson(bytes: ByteArray, maxRecords: Int = 10_000): List<ReadingRecord> {
            val root = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return emptyList()
            }
            val arr = root.optJSONArray("books") ?: return emptyList()
            val out = mutableListOf<ReadingRecord>()
            val n = minOf(arr.length(), maxRecords)
            for (i in 0 until n) {
                val o = arr.optJSONObject(i) ?: continue
                fromJson(o)?.let { out += it }
            }
            return out
        }

        /** Fold [incoming] into [base] by book identity (see [merge]). */
        fun mergeAll(
            base: List<ReadingRecord>,
            incoming: List<ReadingRecord>
        ): List<ReadingRecord> {
            val out = base.toMutableList()
            for (rec in incoming) {
                val idx = out.indexOfFirst { it.sameBookAs(rec) }
                if (idx >= 0) out[idx] = merge(out[idx], rec) else out += rec
            }
            return out
        }
    }
}

/** Build the record for a library book (never called for private books). */
fun readingRecordFor(book: BookEntity, sha256: String, epubId: String?): ReadingRecord =
    ReadingRecord(
        sha256 = sha256,
        epubId = epubId?.trim() ?: "",
        title = book.title,
        fileName = book.originalFileName ?: File(book.filePath).name,
        chapterIndex = book.lastChapterIndex,
        totalChapters = book.totalChapters,
        percent = if (book.totalChapters > 0) {
            (book.lastChapterIndex + 1) * 100.0 / book.totalChapters
        } else {
            0.0
        },
        finished = isCompleted(book),
        favorite = book.isFavorite,
        collection = book.collectionName,
        updatedAt = parseInstantMillis(book.lastOpenedDate)
            ?: parseInstantMillis(book.dateAdded)
            ?: 0L
    )

private fun parseInstantMillis(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

/**
 * The updated book when [record] adds anything (progress never regresses,
 * favorite/collection are only filled in, never cleared), else null.
 * A restored position also restores `lastOpenedDate` from the record when
 * the book has none — completion and percent both gate on it.
 */
fun applyRecordTo(book: BookEntity, record: ReadingRecord): BookEntity? {
    var b = book
    val total = book.totalChapters
    if (total > 0) {
        val target = when {
            record.finished -> total - 1
            record.totalChapters == total -> record.chapterIndex
            record.totalChapters > 0 ->
                Math.round(record.percent / 100.0 * total).toInt() - 1
            else -> record.chapterIndex
        }.coerceIn(0, total - 1)
        if (target > b.lastChapterIndex) {
            b = b.copy(lastChapterIndex = target)
        }
        val needsOpened = record.finished || b.lastChapterIndex > book.lastChapterIndex
        if (needsOpened && b.lastOpenedDate.isNullOrEmpty() && record.updatedAt > 0) {
            b = b.copy(
                lastOpenedDate = Instant.ofEpochMilli(record.updatedAt).toString()
            )
        }
    }
    if (record.favorite && !b.isFavorite) b = b.copy(isFavorite = true)
    if (b.collectionName.isNullOrBlank() && !record.collection.isNullOrBlank()) {
        b = b.copy(collectionName = record.collection)
    }
    return if (b != book) b else null
}

/** Streaming SHA-256 of a file, lowercase hex (same as the backup format). */
fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The OPF `dc:identifier` of an EPUB file, read straight from the zip
 * (container.xml → rootfile → first identifier) with the same XXE
 * hardening as [com.novelfa.reader.parser.EpubParser]. Null on any
 * failure — the identifier is a fallback key, never load-bearing.
 */
fun readEpubIdentifier(epubFile: File): String? = try {
    ZipFile(epubFile).use { zip ->
        fun entryBytes(name: String, cap: Long): ByteArray? {
            val entry = zip.getEntry(name)
                ?: zip.entries().asSequence()
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return null
            if (entry.size > cap) return null
            return zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > cap) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }

        fun parseXml(bytes: ByteArray): Document? {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                runCatching { isNamespaceAware = true }
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
            }
            return try {
                dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                null
            }
        }

        val container = entryBytes("META-INF/container.xml", 1024 * 1024)
            ?.let { parseXml(it) } ?: return null
        val rootfiles = container.getElementsByTagName("rootfile")
        var opfPath: String? = null
        for (i in 0 until rootfiles.length) {
            val p = rootfiles.item(i).attributes
                ?.getNamedItem("full-path")?.nodeValue?.trim()
            if (!p.isNullOrEmpty()) {
                opfPath = p
                break
            }
        }
        val opf = entryBytes(opfPath ?: return null, 10L * 1024 * 1024)
            ?.let { parseXml(it) } ?: return null
        val ids = opf.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "identifier")
        var fallback: String? = null
        for (i in 0 until ids.length) {
            val text = ids.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) {
                // Prefer a URN/UUID-looking identifier over a bare number
                // (some books list an ISBN first and the UUID second).
                if (text.contains(':') || text.contains('-') && text.length >= 32) {
                    return text.take(256)
                }
                if (fallback == null) fallback = text
            }
        }
        fallback?.take(256)
    }
} catch (e: Exception) {
    null
}

/**
 * On-device store of reading records for books NOT currently in the
 * library (orphans from restores and deletions). Library books' records
 * are always rebuilt from the DB at export time; the store only carries
 * records across the gaps. Backed by one small JSON file in filesDir.
 */
class ReadingRecordStore(context: Context) {
    private val file = File(context.filesDir, "reading_records.json")
    private val lock = Any()
    private var cache: MutableList<ReadingRecord>? = null

    private fun loaded(): MutableList<ReadingRecord> {
        cache?.let { return it }
        val list = if (file.isFile) {
            try {
                ReadingRecord.listFromJson(file.readBytes()).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        cache = list
        return list
    }

    private fun persist() {
        try {
            file.writeBytes(ReadingRecord.listToJson(cache.orEmpty()))
        } catch (e: Exception) {
            // A failed store write only loses orphan records — never fatal.
        }
    }

    fun all(): List<ReadingRecord> = synchronized(lock) { loaded().toList() }

    fun isEmpty(): Boolean = synchronized(lock) { loaded().isEmpty() }

    /** Exact-key lookup: sha256 first, then epubId. */
    fun find(sha256: String, epubId: String?): ReadingRecord? = synchronized(lock) {
        val list = loaded()
        if (sha256.isNotBlank()) {
            list.firstOrNull { it.sha256.isNotBlank() && it.sha256 == sha256 }
                ?.let { return@synchronized it }
        }
        if (!epubId.isNullOrBlank()) {
            list.firstOrNull { it.epubId.isNotBlank() && it.epubId == epubId }
                ?.let { return@synchronized it }
        }
        null
    }

    /** Insert or merge one record. */
    fun upsert(record: ReadingRecord) {
        synchronized(lock) {
            val list = loaded()
            val idx = list.indexOfFirst { it.sameBookAs(record) }
            if (idx >= 0) list[idx] = ReadingRecord.merge(list[idx], record)
            else list += record
            persist()
        }
    }

    /** Merge a whole set (e.g. from a restored backup). */
    fun mergeAll(incoming: List<ReadingRecord>) {
        if (incoming.isEmpty()) return
        synchronized(lock) {
            cache = ReadingRecord.mergeAll(loaded(), incoming).toMutableList()
            persist()
        }
    }
}
