package com.novelfa.reader.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.RankService
import com.novelfa.reader.theme.XpStore
import com.novelfa.reader.theme.fontDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Why a backup file was rejected (mapped to localized strings in the UI). */
enum class BackupError {
    NOT_A_BACKUP, TOO_NEW, CORRUPT, FONT_INVALID, IMAGE_INVALID, TOO_LARGE, UNREADABLE
}

sealed interface InspectResult {
    data class Ok(val preview: BackupPreview) : InspectResult
    data class Failed(val error: BackupError) : InspectResult
}

/** One validated font payload inside a backup (bytes live in [file]). */
data class BackupFont(
    val zipPath: String,
    val displayName: String,
    val familyName: String,
    val originalFileName: String,
    val sha256: String,
    val file: File
)

/**
 * A fully validated backup, ready to show in the confirm dialog and apply.
 * Nothing has been applied yet. Payload bytes live in [payloadDir] (app
 * cache); call [BackupService.discard] when the preview is abandoned.
 */
data class BackupPreview(
    val createdAt: String,
    val platform: String,
    val appVersion: String,
    val profileName: String,
    val maxRank: Int,
    val finishedBooks: Int,
    /** XP total carried by the backup (-1 = old backup without XP). */
    val totalXp: Int = -1,
    /** Portable (content-keyed) XP ledger ("" = old backup without it). */
    val xpLedger: String = "",
    val secretThemes: List<String>,
    val avatar: File?,
    val fonts: List<BackupFont>,
    /** Reading records carried by a format-v2 backup (empty for v1). */
    val records: List<ReadingRecord> = emptyList(),
    val payloadDir: File
)

data class BackupApplyResult(
    val fontsAdded: Int,
    val fontsSkipped: Int,
    val fontsFailed: Int,
    val avatarApplied: Boolean,
    /** Effective rank index before the merge (for the result dialog). */
    val rankBefore: Int,
    /** Effective rank index after the merge. */
    val rankAfter: Int,
    /** Effective finished-books count before the merge. */
    val finishedBefore: Int,
    /** Finished-books count carried by the backup itself. */
    val finishedAdded: Int,
    /** Merged total: [finishedBefore] + [finishedAdded]. */
    val finishedTotal: Int,
    /** Library books whose reading state was updated from a record. */
    val recordsApplied: Int = 0,
    /** Records kept in the store for books not (yet) in the library. */
    val recordsKept: Int = 0
)

/**
 * Backup export / inspect / restore for the portable `.novelfabak` format
 * (spec: Novelfa-Backup-Plan.md — the format is shared byte-for-byte with
 * the Windows app; do not change field names, the integrity algorithm or
 * the validation rules without bumping formatVersion on both platforms).
 *
 * Contents: user-added fonts only (never the bundled ones), the profile
 * avatar, profile name, MaxRank, secret-unlocked themes and the lifetime
 * finished-books count (as the FinishedBooksRestored floor). Since format
 * v2 the backup also carries per-book READING RECORDS (records.json) for
 * non-private books — identity + reading position only, so a re-imported
 * book resumes where it stopped. The books themselves, covers, the
 * library DB and the PIN hash are NEVER included.
 *
 * Validation proves "an intact, untampered Novelfa backup" (schema + magic
 * + per-file SHA-256 + a whole-payload integrity checksum). There is no
 * shared secret between the apps, so this detects corruption/tampering,
 * not a determined forger — same note as the plan.
 */
class BackupService(
    private val context: Context,
    private val prefs: PrefsStore,
    private val dao: BookDao,
    private val rankPrefs: PrefsStoreRankPrefs
) {
    private val fontStore = FontStore(context)

    companion object {
        const val FORMAT_ID = "novelfa-backup"
        const val FORMAT_VERSION = 2

        // Validation caps (plan §5).
        const val MAX_TOTAL_BYTES = 120L * 1024 * 1024
        const val MAX_FONT_BYTES = 25L * 1024 * 1024
        const val MAX_AVATAR_BYTES = 15L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val MAX_FONTS = 200
        const val MAX_FINISHED_BOOKS = 100000
        const val MAX_RECORDS_BYTES = 16L * 1024 * 1024
        const val MAX_RECORDS = 10_000

        private val AVATAR_NAME = Regex("avatar\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)
        private val FONT_NAME = Regex("fonts/[A-Za-z0-9_\\-]+\\.(ttf|otf)", RegexOption.IGNORE_CASE)
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        private val SECRET_ID = Regex("[A-Za-z0-9_\\-]{1,64}")

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * Whole-backup integrity (plan §3.3 — must match the Windows app
         * byte-for-byte): one `<path>:<sha256>` line per payload file using
         * the COMPUTED lowercase hashes, sorted by path, plus a final
         * `profile:` line; joined with "\n", no trailing newline.
         */
        fun computeIntegrity(
            pathHashes: Map<String, String>,
            profileName: String,
            maxRank: Int,
            finishedBooks: Int,
            secretThemes: List<String>
        ): String {
            val lines = pathHashes.entries
                .sortedBy { it.key }
                .map { "${it.key}:${it.value}" }
                .toMutableList()
            val secrets = secretThemes.sorted().joinToString(",")
            lines += "profile:$profileName|$maxRank|$finishedBooks|$secrets"
            return sha256Hex(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        }

        /**
         * Font magic bytes (plan §5.8): TTF 00 01 00 00 / 'true' / 'typ1',
         * TTC 'ttcf', OTF 'OTTO'.
         */
        fun isFontMagic(header: ByteArray): Boolean {
            if (header.size < 4) return false
            if (header[0] == 0.toByte() && header[1] == 1.toByte() &&
                header[2] == 0.toByte() && header[3] == 0.toByte()
            ) return true
            val s = String(header, 0, 4, Charsets.US_ASCII)
            return s == "true" || s == "typ1" || s == "ttcf" || s == "OTTO"
        }

        /**
         * A stored-file base name for a restored font, Android importFont
         * rules (no separators/traversal/markup). Falls back through
         * displayName → familyName → original name → "font".
         */
        fun fontBaseName(displayName: String, familyName: String, originalFileName: String): String {
            val candidates = listOf(
                displayName,
                familyName,
                originalFileName.substringBeforeLast('.')
            )
            for (c in candidates) {
                val clean = c.trim()
                if (clean.isNotEmpty() && clean.length <= 60 && !clean.startsWith(".") &&
                    !clean.contains('/') && !clean.contains('\\') && !clean.contains("..") &&
                    !clean.contains('<') && !clean.contains('>')
                ) {
                    return clean
                }
            }
            return "font"
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** Names of the fonts bundled in assets/fonts (never backed up). */
    private fun bundledFontNames(): Set<String> =
        try {
            context.assets.list("fonts")?.toSet()
        } catch (e: Exception) {
            null
        } ?: setOf("Vazirmatn-Regular.ttf", "Vazirmatn-Bold.ttf", "B Koodak Bold_0.ttf")

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun sha256Of(file: File): String {
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

    suspend fun exportBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        // --- collect payload ---
        val bundled = bundledFontNames()
        val seenHashes = mutableSetOf<String>()
        val fontFiles = mutableListOf<File>()
        for (f in fontStore.list()) {
            val ext = f.extension.lowercase()
            if (ext != "ttf" && ext != "otf") continue
            if (bundled.contains(f.name)) continue
            fontFiles += f
        }
        val avatarPath = prefs.avatarPath.first()
        val avatarFile = avatarPath.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }

        // --- hash everything first (manifest needs the hashes) ---
        data class Payload(val zipPath: String, val src: File, val sha: String)
        val payloads = mutableListOf<Payload>()
        val pathHashes = mutableMapOf<String, String>()
        if (avatarFile != null) {
            val ext = avatarFile.extension.lowercase().ifBlank { "jpg" }
            val zp = "avatar.$ext"
            val sha = sha256Of(avatarFile)
            payloads += Payload(zp, avatarFile, sha)
            pathHashes[zp] = sha
        }
        var fontIndex = 0
        val fontMeta = mutableListOf<JSONObject>()
        for (f in fontFiles) {
            val sha = sha256Of(f)
            if (!seenHashes.add(sha)) continue // same font twice on disk -> once in backup
            fontIndex++
            val zp = "fonts/%03d.%s".format(Locale.US, fontIndex, f.extension.lowercase())
            payloads += Payload(zp, f, sha)
            pathHashes[zp] = sha
            fontMeta += JSONObject()
                .put("file", zp)
                .put("displayName", fontDisplayName(f.name))
                // Android has no public API for a font file's internal
                // family name; the file base name is the best available
                // value (Windows re-reads the real family from the file
                // on restore and prefers it, per plan §6).
                .put("familyName", f.nameWithoutExtension)
                .put("originalFileName", f.name)
                .put("sha256", sha)
                .put("bytes", f.length())
        }

        // --- reading records (format v2) ---
        // The live library's non-private books (identity + position),
        // merged with orphan records kept on-device for books that are
        // not currently in the library. Private books are excluded on
        // purpose: the backup file is not encrypted.
        val recordStore = ReadingRecordStore(context)
        val libraryRecords = try {
            dao.allBooks().first()
                .filter { !it.isPrivate }
                .mapNotNull { book ->
                    val f = File(book.filePath)
                    if (!f.isFile) null
                    else readingRecordFor(book, sha256Of(f), readEpubIdentifier(f))
                }
        } catch (e: Exception) {
            emptyList()
        }
        val allRecords = ReadingRecord.mergeAll(recordStore.all(), libraryRecords)
            .sortedWith(compareBy({ it.title.lowercase() }, { it.sha256 }))
        var recordsPayload: Payload? = null
        var recordsBytes = 0L
        if (allRecords.isNotEmpty()) {
            val bytes = ReadingRecord.listToJson(allRecords)
            val tmp = File(context.cacheDir, "novelfa_records_out.json")
            tmp.writeBytes(bytes)
            val sha = sha256Hex(bytes)
            recordsPayload = Payload("records.json", tmp, sha)
            payloads += recordsPayload
            pathHashes["records.json"] = sha
            recordsBytes = bytes.size.toLong()
        }

        // --- profile ---
        rankPrefs.awaitLoaded()
        val rankService = RankService(rankPrefs)
        val maxRank = rankService.maxRank
        val secrets = rankPrefs.getString(RankService.SECRET_KEY, "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val libraryFinished = dao.allBooks().first().count { !it.isPrivate && isCompleted(it) }
        val finishedBooks = maxOf(libraryFinished, prefs.getFinishedBooksRestored())
        // XP travels with the backup (max-merge on restore, never summed:
        // summing would double-count the same books across devices). The
        // field is additive: old readers ignore it, and the integrity hash
        // deliberately does NOT cover it (it covers profile/rank/books/
        // secrets + payload hashes, unchanged, so Windows imports still
        // verify byte-for-byte).
        val totalXp = XpStore(rankPrefs).totalXp
        // Portable XP ledger: only content-keyed entries travel, so
        // a restored backup can never re-earn units on a new device
        // (UUID entries are device-local by definition and are left
        // out). Additive like totalXp: old readers ignore the field,
        // and computeIntegrity does not cover it.
        val xpLedger = XpStore(rankPrefs).contentLedgerSerialized()
        val profileName = prefs.profileName.first()

        val avatarPayload = payloads.firstOrNull()
        val manifest = JSONObject()
            .put("format", FORMAT_ID)
            .put("formatVersion", FORMAT_VERSION)
            .put("app", "Novelfa")
            .put("platform", "android")
            .put("appVersion", appVersion())
            .put(
                "createdAt",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(System.currentTimeMillis())
            )
            .put(
                "profile", JSONObject()
                    .put("name", profileName)
                    .put("maxRank", maxRank)
                    .put("finishedBooks", finishedBooks)
                    .put("totalXp", totalXp)
                    .put("xpLedger", xpLedger)
                    .put("secretThemes", JSONArray(secrets))
            )
            .put(
                "avatar",
                if (avatarFile != null && avatarPayload != null) {
                    JSONObject()
                        .put("file", avatarPayload.zipPath)
                        .put("sha256", avatarPayload.sha)
                        .put("bytes", avatarFile.length())
                } else {
                    JSONObject.NULL
                }
            )
            .put("fonts", JSONArray().apply { fontMeta.forEach { put(it) } })
            .put(
                "records",
                if (recordsPayload != null) {
                    JSONObject()
                        .put("file", "records.json")
                        .put("sha256", recordsPayload.sha)
                        .put("bytes", recordsBytes)
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "integrity",
                computeIntegrity(pathHashes, profileName, maxRank, finishedBooks, secrets)
            )

        // --- write the zip ---
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in payloads) {
                zip.putNextEntry(ZipEntry(p.zipPath))
                p.src.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        recordsPayload?.src?.delete()
    }

    // ------------------------------------------------------------------
    // Inspect (full validation — nothing is applied here)
    // ------------------------------------------------------------------

    private fun payloadDir(): File =
        File(context.cacheDir, "novelfa_backup_payload").apply { mkdirs() }

    private fun clearPayloadDir() {
        payloadDir().listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Abandon a preview without applying it (dialog cancelled). */
    fun discard(preview: BackupPreview) {
        preview.payloadDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun fail(error: BackupError): InspectResult {
        clearPayloadDir()
        return InspectResult.Failed(error)
    }

    suspend fun inspectBackup(input: InputStream): InspectResult = withContext(Dispatchers.IO) {
        clearPayloadDir()
        // Copy the incoming stream to a temp file first (capped), so the
        // zip can be read with random access: manifest first, payload next.
        val tmpZip = File(context.cacheDir, "novelfa_backup_incoming.zip")
        try {
            var copied = 0L
            tmpZip.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) {
                        return@withContext fail(BackupError.TOO_LARGE)
                    }
                    o.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            return@withContext fail(BackupError.UNREADABLE)
        }

        val zip = try {
            ZipFile(tmpZip)
        } catch (e: Exception) {
            return@withContext fail(BackupError.NOT_A_BACKUP)
        }

        zip.use {
            // --- manifest ---
            val manifestEntry = zip.getEntry("manifest.json")
                ?: return@withContext fail(BackupError.NOT_A_BACKUP)
            val manifestBytes = try {
                readEntryCapped(zip, manifestEntry, MAX_MANIFEST_BYTES)
            } catch (e: Exception) {
                return@withContext fail(BackupError.CORRUPT)
            } ?: return@withContext fail(BackupError.TOO_LARGE)
            val manifest = try {
                JSONObject(String(manifestBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return@withContext fail(BackupError.NOT_A_BACKUP)
            }
            if (manifest.optString("format") != FORMAT_ID) {
                return@withContext fail(BackupError.NOT_A_BACKUP)
            }
            if (!manifest.has("formatVersion")) {
                return@withContext fail(BackupError.CORRUPT)
            }
            val version = manifest.optInt("formatVersion", -1)
            if (version > FORMAT_VERSION) {
                return@withContext fail(BackupError.TOO_NEW)
            }
            if (version !in 1..FORMAT_VERSION) {
                return@withContext fail(BackupError.CORRUPT)
            }

            // --- profile values, range-checked ---
            val profile = manifest.optJSONObject("profile")
                ?: return@withContext fail(BackupError.CORRUPT)
            val profileName = profile.optString("name", "")
            val maxRank = profile.optInt("maxRank", Int.MIN_VALUE)
            val finishedBooks = profile.optInt("finishedBooks", -1)
            val totalXp = profile.optInt("totalXp", -1)
            // Auxiliary like records: a malformed ledger drops the
            // ledger, never the whole backup (XpLedger.parse is
            // lenient and skips bad entries anyway). Length-capped
            // so a hostile backup cannot smuggle a huge string in.
            val xpLedger = profile.optString("xpLedger", "")
                .takeIf { it.length <= 1_000_000 } ?: ""
            if (maxRank !in -1..RankService.Ranks.lastIndex) {
                return@withContext fail(BackupError.CORRUPT)
            }
            if (finishedBooks !in 0..MAX_FINISHED_BOOKS) {
                return@withContext fail(BackupError.CORRUPT)
            }
            if (totalXp !in -1..10_000_000) {
                return@withContext fail(BackupError.CORRUPT)
            }
            val secrets = mutableListOf<String>()
            val secretsArr = profile.optJSONArray("secretThemes")
                ?: return@withContext fail(BackupError.CORRUPT)
            for (i in 0 until secretsArr.length()) {
                val id = secretsArr.optString(i, "")
                if (!SECRET_ID.matches(id)) {
                    return@withContext fail(BackupError.CORRUPT)
                }
                secrets += id
            }

            // --- declared payload set ---
            data class Declared(val zipPath: String, val sha: String, val bytes: Long, val isFont: Boolean, val cap: Long)
            val declared = mutableListOf<Declared>()
            var avatarDeclared: Declared? = null
            if (!manifest.isNull("avatar")) {
                val a = manifest.optJSONObject("avatar")
                    ?: return@withContext fail(BackupError.CORRUPT)
                val p = a.optString("file", "")
                val sha = a.optString("sha256", "")
                val bytes = a.optLong("bytes", -1)
                if (!AVATAR_NAME.matches(p) || !SHA256_HEX.matches(sha) || bytes < 0) {
                    return@withContext fail(BackupError.CORRUPT)
                }
                avatarDeclared = Declared(p, sha.lowercase(), bytes, isFont = false, cap = MAX_AVATAR_BYTES)
                declared += avatarDeclared
            }
            val fontsArr = manifest.optJSONArray("fonts")
                ?: return@withContext fail(BackupError.CORRUPT)
            if (fontsArr.length() > MAX_FONTS) {
                return@withContext fail(BackupError.TOO_LARGE)
            }
            val fontMetas = mutableListOf<JSONObject>()
            for (i in 0 until fontsArr.length()) {
                val f = fontsArr.optJSONObject(i)
                    ?: return@withContext fail(BackupError.CORRUPT)
                val p = f.optString("file", "")
                val sha = f.optString("sha256", "")
                val bytes = f.optLong("bytes", -1)
                if (!FONT_NAME.matches(p) || !SHA256_HEX.matches(sha) || bytes < 0) {
                    return@withContext fail(BackupError.CORRUPT)
                }
                declared += Declared(p, sha.lowercase(), bytes, isFont = true, cap = MAX_FONT_BYTES)
                fontMetas += f
            }
            var recordsDeclared: Declared? = null
            if (version == FORMAT_VERSION && !manifest.isNull("records")) {
                val r = manifest.optJSONObject("records")
                    ?: return@withContext fail(BackupError.CORRUPT)
                val p = r.optString("file", "")
                val sha = r.optString("sha256", "")
                val bytes = r.optLong("bytes", -1)
                if (p != "records.json" || !SHA256_HEX.matches(sha) || bytes < 0) {
                    return@withContext fail(BackupError.CORRUPT)
                }
                recordsDeclared = Declared(
                    p, sha.lowercase(), bytes, isFont = false, cap = MAX_RECORDS_BYTES
                )
                declared += recordsDeclared
            }
            if (declared.distinctBy { it.zipPath }.size != declared.size) {
                return@withContext fail(BackupError.CORRUPT)
            }

            // --- zip contents must be exactly manifest.json + declared ---
            val entryNames = zip.entries().asSequence().map { it.name }.toList()
            if (entryNames.size != entryNames.distinct().size) {
                return@withContext fail(BackupError.CORRUPT)
            }
            val expected = declared.map { it.zipPath }.toSet() + "manifest.json"
            if (entryNames.toSet() != expected) {
                return@withContext fail(BackupError.CORRUPT)
            }

            // --- extract + verify every payload (size, hash, kind) ---
            val dir = payloadDir()
            val computedHashes = mutableMapOf<String, String>()
            val extracted = mutableMapOf<String, File>()
            var totalBytes = 0L
            for (d in declared) {
                val cap = d.cap
                if (d.bytes > cap) {
                    return@withContext fail(BackupError.TOO_LARGE)
                }
                val entry = zip.getEntry(d.zipPath)
                    ?: return@withContext fail(BackupError.CORRUPT)
                val dest = File(dir, d.zipPath.substringAfterLast('/'))
                val actual = try {
                    extractCapped(zip, entry, dest, cap)
                } catch (e: Exception) {
                    return@withContext fail(BackupError.CORRUPT)
                } ?: return@withContext fail(BackupError.TOO_LARGE)
                totalBytes += actual
                if (totalBytes > MAX_TOTAL_BYTES) {
                    return@withContext fail(BackupError.TOO_LARGE)
                }
                if (actual != d.bytes) {
                    return@withContext fail(BackupError.CORRUPT)
                }
                val sha = sha256Of(dest)
                if (sha != d.sha) {
                    return@withContext fail(BackupError.CORRUPT)
                }
                computedHashes[d.zipPath] = sha
                extracted[d.zipPath] = dest
                if (d.isFont) {
                    val header = ByteArray(4)
                    dest.inputStream().use { stream ->
                        var read = 0
                        while (read < 4) {
                            val n = stream.read(header, read, 4 - read)
                            if (n < 0) break
                            read += n
                        }
                    }
                    if (!isFontMagic(header)) {
                        return@withContext fail(BackupError.FONT_INVALID)
                    }
                }
            }

            // --- avatar must be a real image ---
            var avatarFile: File? = null
            if (avatarDeclared != null) {
                avatarFile = extracted[avatarDeclared.zipPath]
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(avatarFile!!.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    return@withContext fail(BackupError.IMAGE_INVALID)
                }
            }

            // --- whole-backup integrity ---
            val expectedIntegrity = manifest.optString("integrity", "")
            val actualIntegrity = computeIntegrity(
                computedHashes, profileName, maxRank, finishedBooks, secrets
            )
            if (!SHA256_HEX.matches(expectedIntegrity) ||
                !expectedIntegrity.equals(actualIntegrity, ignoreCase = true)
            ) {
                return@withContext fail(BackupError.CORRUPT)
            }

            val fonts = declared.filter { it.isFont }.map { d ->
                val meta = fontMetas.first {
                    it.optString("file", "") == d.zipPath
                }
                BackupFont(
                    zipPath = d.zipPath,
                    displayName = meta.optString("displayName", ""),
                    familyName = meta.optString("familyName", ""),
                    originalFileName = meta.optString("originalFileName", ""),
                    sha256 = d.sha,
                    file = extracted.getValue(d.zipPath)
                )
            }
            // Records are auxiliary: integrity above already proves the
            // bytes are the writer's; a parse hiccup drops the records,
            // never the whole backup.
            val records = if (recordsDeclared != null) {
                val f = extracted[recordsDeclared.zipPath]
                if (f != null) {
                    try {
                        ReadingRecord.listFromJson(f.readBytes(), MAX_RECORDS)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            InspectResult.Ok(
                BackupPreview(
                    createdAt = manifest.optString("createdAt", ""),
                    platform = manifest.optString("platform", ""),
                    appVersion = manifest.optString("appVersion", ""),
                    profileName = profileName,
                    maxRank = maxRank,
                    finishedBooks = finishedBooks,
                    totalXp = totalXp,
                    xpLedger = xpLedger,
                    secretThemes = secrets,
                    avatar = avatarFile,
                    fonts = fonts,
                    records = records,
                    payloadDir = dir
                )
            )
        }
    }

    /** Read a whole zip entry with a hard cap; null when the cap is hit. */
    private fun readEntryCapped(zip: ZipFile, entry: ZipEntry, cap: Long): ByteArray? {
        zip.getInputStream(entry).use { input ->
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
            return out.toByteArray()
        }
    }

    /** Extract one entry to [dest] with a hard cap; null when the cap is hit. */
    private fun extractCapped(zip: ZipFile, entry: ZipEntry, dest: File, cap: Long): Long? {
        zip.getInputStream(entry).use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > cap) return null
                    output.write(buf, 0, n)
                }
                return total
            }
        }
    }

    // ------------------------------------------------------------------
    // Apply (only ever called with a fully validated preview)
    // ------------------------------------------------------------------

    suspend fun applyBackup(preview: BackupPreview): BackupApplyResult =
        withContext(Dispatchers.IO) {
            var added = 0
            var skipped = 0
            var failed = 0

            // --- fonts (dedupe by content hash against everything on disk) ---
            val existingHashes = mutableMapOf<String, String>() // sha -> file name
            for (f in fontStore.list()) {
                existingHashes.putIfAbsent(sha256Of(f), f.name)
            }
            val existingNames = fontStore.list()
                .map { it.name.lowercase() }
                .toMutableSet()
            for (font in preview.fonts) {
                if (existingHashes.containsKey(font.sha256)) {
                    skipped++
                    continue
                }
                val ext = font.zipPath.substringAfterLast('.').lowercase()
                val base = fontBaseName(font.displayName, font.familyName, font.originalFileName)
                var destName = "$base.$ext"
                var n = 2
                while (existingNames.contains(destName.lowercase())) {
                    destName = "$base${n++}.$ext"
                }
                val dest = fontStore.addFont(destName, font.file)
                // Registration check (plan §11): a file that passed the
                // magic check but cannot produce a Typeface is rolled back;
                // the rest of the restore still proceeds.
                val ok = try {
                    Typeface.createFromFile(dest) != null
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    added++
                    existingNames += destName.lowercase()
                    existingHashes[font.sha256] = destName
                } else {
                    fontStore.deleteFont(destName)
                    failed++
                }
            }

            // --- avatar (replace) ---
            // Best effort, never fatal: an avatar copy failure used to
            // abort the whole restore AFTER fonts were already applied,
            // while the UI reported a generic "unreadable" error.
            var avatarApplied = false
            if (preview.avatar != null) {
                avatarApplied = runCatching {
                    val dir = File(context.filesDir, "avatars").apply { mkdirs() }
                    val dest = File(dir, "avatar.jpg")
                    preview.avatar.copyTo(dest, overwrite = true)
                    prefs.setAvatarPath(dest.absolutePath)
                    true
                }.getOrDefault(false)
            }

            // --- profile + rank merge ---
            // Finished books are SUMMED (Aster's spec): the backup's count is
            // added on top of the current effective count (library-derived or
            // the restored floor, whichever is higher) and the sum becomes
            // the new persisted floor. Rank is unified across both sides —
            // the max of the current rank, the backup's stored rank and the
            // rank implied by the summed count — and never goes down.
            // Secret themes union (in RankService via secretUnlock).
            val libraryFinished = dao.allBooks().first()
                .count { !it.isPrivate && isCompleted(it) }
            val finishedBefore = maxOf(
                libraryFinished,
                prefs.getFinishedBooksRestored()
            )
            val finishedTotal = (finishedBefore + preview.finishedBooks)
                .coerceAtMost(MAX_FINISHED_BOOKS)
            if (preview.profileName.isNotBlank()) {
                prefs.setProfileName(preview.profileName)
            }
            rankPrefs.awaitLoaded()
            val service = RankService(rankPrefs)
            val rankBefore = maxOf(
                service.maxRank,
                RankService.rankIndexForStatic(finishedBefore)
            )
            val targetRank = maxOf(
                rankBefore,
                preview.maxRank,
                RankService.rankIndexForStatic(finishedTotal)
            )
            if (targetRank > service.maxRank) {
                service.maxRank = targetRank
            }
            preview.secretThemes.forEach { service.secretUnlock(it) }
            // XP: max-merge only (see export). Celebrated XP is left alone
            // so a restored gain celebrates on the next home visit.
            // The portable ledger merges alongside the total: units
            // already credited on another device stay credited here,
            // so restoring a backup can never re-earn them. Total XP
            // itself is never summed from the ledger — it stays the
            // max of the two totals, as before.
            if (preview.totalXp >= 0) {
                val xpStore = XpStore(rankPrefs)
                if (preview.totalXp > xpStore.totalXp) xpStore.totalXp = preview.totalXp
                if (preview.xpLedger.isNotBlank()) xpStore.mergeContentLedger(preview.xpLedger)
            }
            prefs.raiseFinishedBooksRestored(finishedTotal)

            // --- reading records (format v2) ---
            // Merge into the on-device store first (orphans included),
            // then apply whatever matches a book already in the library.
            // Private books are never touched.
            val recordStore = ReadingRecordStore(context)
            recordStore.mergeAll(preview.records)
            var recordsApplied = 0
            val matchedKeys = mutableSetOf<String>()
            if (preview.records.isNotEmpty()) {
                for (book in dao.allBooks().first()) {
                    if (book.isPrivate) continue
                    val f = File(book.filePath)
                    if (!f.isFile) continue
                    val rec = recordStore.find(sha256Of(f), readEpubIdentifier(f))
                        ?: continue
                    if (rec.sha256.isNotBlank()) matchedKeys += rec.sha256
                    if (rec.epubId.isNotBlank()) matchedKeys += rec.epubId
                    val updated = applyRecordTo(book, rec)
                    if (updated != null) {
                        dao.update(updated)
                        recordsApplied++
                    }
                }
            }
            val recordsKept = preview.records.count { rec ->
                (rec.sha256.isBlank() || rec.sha256 !in matchedKeys) &&
                    (rec.epubId.isBlank() || rec.epubId !in matchedKeys)
            }

            discard(preview)
            BackupApplyResult(
                added, skipped, failed, avatarApplied,
                rankBefore = rankBefore,
                rankAfter = targetRank,
                finishedBefore = finishedBefore,
                finishedAdded = preview.finishedBooks,
                finishedTotal = finishedTotal,
                recordsApplied = recordsApplied,
                recordsKept = recordsKept
            )
        }
}
