package com.novelfa.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novelfa.reader.data.PrefsStoreRankPrefs
import com.novelfa.reader.domain.RankService
import com.novelfa.reader.domain.XpStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup + restore tests (step 3 of the test plan): export → inspect →
 * apply round-trip, tampered files, version/format rejections, range
 * checks, and the merge semantics (finished-books summing, rank max,
 * XP max-merge, record merge, private-book exclusion).
 *
 * These are instrumented tests: BackupService needs a real Context
 * (files, DataStore, BitmapFactory). They are compile-verified in the
 * VM; execution needs a device or emulator.
 *
 * Test isolation note: PrefsStoreRankPrefs keeps a process-wide cache
 * and DataStore/fonts/record-store files live in the shared test-app
 * dirs, so every test sets exactly the state it asserts and [cleanup]
 * wipes the file-backed state. Nothing here assumes a fresh process.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private lateinit var ctx: Context
    private lateinit var prefs: PrefsStore
    private lateinit var rankPrefs: PrefsStoreRankPrefs
    private val dbs = mutableListOf<ReaderDb>()

    @Before
    fun setup() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        prefs = PrefsStore(ctx)
        rankPrefs = PrefsStoreRankPrefs(prefs)
        rankPrefs.awaitLoaded()
        cleanupFiles()
    }

    @After
    fun teardown() {
        dbs.forEach { runCatching { it.close() } }
        dbs.clear()
        cleanupFiles()
    }

    private fun cleanupFiles() {
        File(ctx.filesDir, "fonts").listFiles()?.forEach { it.delete() }
        File(ctx.filesDir, "avatars").deleteRecursively()
        File(ctx.filesDir, "reading_records.json").delete()
        File(ctx.cacheDir, "novelfa_backup_payload").deleteRecursively()
        File(ctx.cacheDir, "novelfa_backup_incoming.zip").delete()
        File(ctx.cacheDir, "novelfa_records_out.json").delete()
        File(ctx.cacheDir, "backup-test").deleteRecursively()
    }

    private fun newDao(): BookDao {
        val db = Room.inMemoryDatabaseBuilder(ctx, ReaderDb::class.java).build()
        dbs += db
        return db.bookDao()
    }

    private fun service(dao: BookDao) = BackupService(ctx, prefs, dao, rankPrefs)

    private fun testDir(): File = File(ctx.cacheDir, "backup-test").apply { mkdirs() }

    private fun writeFile(name: String, bytes: ByteArray): File =
        File(testDir(), name).apply { writeBytes(bytes) }

    /** A real TTF (bundled Vazirmatn) copied under a non-bundled name. */
    private fun addUserFont(name: String = "MyTestFont.ttf"): File {
        val tmp = File(testDir(), "font-src.ttf")
        ctx.assets.open("fonts/Vazirmatn-Regular.ttf").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        return FontStore(ctx).addFont(name, tmp)
    }

    /** A real 16x16 PNG, written to a file the prefs can point at. */
    private fun writePngAvatar(): File {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val f = File(testDir(), "avatar-src.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    private fun bookFile(name: String): File =
        writeFile(name, "fake book content $name".toByteArray(Charsets.UTF_8))

    // --- crafted backups ------------------------------------------------

    /** A zip with exactly [manifest] + [payloads] (manifest.json first). */
    private fun craftBackup(manifest: JSONObject, payloads: Map<String, ByteArray>): ByteArray =
        zipOf(mapOf("manifest.json" to manifest.toString(2).toByteArray(Charsets.UTF_8)) + payloads)

    /** A zip with exactly these entries, in order. */
    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun profileJson(
        name: String,
        maxRank: Int,
        finishedBooks: Int,
        secrets: List<String> = emptyList(),
        totalXp: Int = -1,
        xpLedger: String = ""
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("maxRank", maxRank)
        .put("finishedBooks", finishedBooks)
        .put("totalXp", totalXp)
        .put("xpLedger", xpLedger)
        .put("secretThemes", JSONArray().apply { secrets.forEach { put(it) } })

    private fun manifestJson(
        version: Int = 2,
        profile: JSONObject,
        fonts: JSONArray = JSONArray(),
        avatar: Any? = JSONObject.NULL,
        records: Any? = JSONObject.NULL,
        integrity: String,
        format: String = BackupService.FORMAT_ID
    ): JSONObject {
        val m = JSONObject()
            .put("format", format)
            .put("formatVersion", version)
            .put("app", "Novelfa")
            .put("platform", "android")
            .put("appVersion", "")
            .put("createdAt", "2026-01-01T00:00:00Z")
            .put("profile", profile)
            .put("fonts", fonts)
            .put("integrity", integrity)
        if (avatar !== null) m.put("avatar", avatar) else m.put("avatar", JSONObject.NULL)
        if (records !== null) m.put("records", records) else m.put("records", JSONObject.NULL)
        return m
    }

    /** Rewrites a zip: [replace] maps entry name → new bytes (null = drop), plus [extra] entries. */
    private fun rewriteZip(
        bytes: ByteArray,
        replace: Map<String, ByteArray?> = emptyMap(),
        extra: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name
                    val content = zin.readBytes()
                    if (name in replace) {
                        val repl = replace.getValue(name)
                        if (repl != null) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(repl)
                            zip.closeEntry()
                        }
                        // null = dropped
                    } else {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content)
                        zip.closeEntry()
                    }
                }
            }
            for ((name, content) in extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun expectFailed(bytes: ByteArray, error: BackupError, dao: BookDao = newDao()) =
        runBlocking {
            when (val r = service(dao).inspectBackup(ByteArrayInputStream(bytes))) {
                is InspectResult.Failed -> assertEquals(error, r.error)
                is InspectResult.Ok -> fail("expected $error, got Ok")
            }
        }

    private fun expectOk(bytes: ByteArray, dao: BookDao = newDao()): BackupPreview =
        runBlocking {
            when (val r = service(dao).inspectBackup(ByteArrayInputStream(bytes))) {
                is InspectResult.Ok -> r.preview
                is InspectResult.Failed -> throw AssertionError("expected Ok, got ${r.error}")
            }
        }

    private fun exportBytes(dao: BookDao): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        service(dao).exportBackup(out)
        out.toByteArray()
    }

    // --- round-trip ------------------------------------------------------

    @Test
    fun roundTrip_exportInspectApply_restoresEverything() = runBlocking {
        // --- device A state ---
        val daoA = newDao()
        val file1 = bookFile("roundtrip-book1.bin")
        val file2 = bookFile("roundtrip-book2.bin")
        val file3 = bookFile("roundtrip-book3.bin")
        daoA.insert(
            BookEntity(
                id = "b1", title = "کتاب کامل", filePath = file1.absolutePath,
                totalChapters = 10, lastChapterIndex = 5,
                dateAdded = "2026-01-01T00:00:00Z",
                lastOpenedDate = "2026-02-01T00:00:00Z",
                isFavorite = true, collectionName = "خوانده‌شده"
            )
        )
        daoA.insert(
            BookEntity(
                id = "b2", title = "کتاب خصوصی", filePath = file2.absolutePath,
                totalChapters = 10, lastChapterIndex = 8,
                dateAdded = "2026-01-01T00:00:00Z",
                lastOpenedDate = "2026-02-01T00:00:00Z", isPrivate = true
            )
        )
        daoA.insert(
            BookEntity(
                id = "b3", title = "کتاب تمام‌شده", filePath = file3.absolutePath,
                totalChapters = 10, lastChapterIndex = 9,
                dateAdded = "2026-01-01T00:00:00Z",
                lastOpenedDate = "2026-02-01T00:00:00Z"
            )
        )
        prefs.setProfileName("Aster")
        prefs.setAvatarPath(writePngAvatar().absolutePath)
        val fontFile = addUserFont()
        val fontSha = BackupService.sha256Hex(fontFile.readBytes())
        val rank = RankService(rankPrefs)
        rank.maxRank = 3
        rankPrefs.setString(RankService.SECRET_KEY, "")
        rank.secretUnlock("testsecret")
        XpStore(rankPrefs).totalXp = 150
        val floorBeforeExport = prefs.getFinishedBooksRestored()
        // libraryFinished = 1 (b3 only; private b2 excluded).

        val bytes = exportBytes(daoA)

        // --- inspect ---
        val preview = expectOk(bytes, daoA)
        assertEquals("Aster", preview.profileName)
        assertEquals(3, preview.maxRank)
        assertEquals(maxOf(1, floorBeforeExport), preview.finishedBooks)
        assertEquals(150, preview.totalXp)
        assertEquals(listOf("testsecret"), preview.secretThemes)
        assertEquals(1, preview.fonts.size)
        assertEquals("MyTestFont.ttf", preview.fonts[0].originalFileName)
        assertEquals(fontSha, preview.fonts[0].sha256)
        assertTrue(preview.avatar != null)
        // Records: b1 + b3 only; the private book's record must not leak.
        assertEquals(setOf("کتاب کامل", "کتاب تمام‌شده"), preview.records.map { it.title }.toSet())

        // --- device B state (fresh library, same files, no progress) ---
        val daoB = newDao()
        daoB.insert(
            BookEntity(
                id = "b1", title = "کتاب کامل", filePath = file1.absolutePath,
                totalChapters = 10, dateAdded = "2026-01-01T00:00:00Z"
            )
        )
        daoB.insert(
            BookEntity(
                id = "b2", title = "کتاب خصوصی", filePath = file2.absolutePath,
                totalChapters = 10, dateAdded = "2026-01-01T00:00:00Z", isPrivate = true
            )
        )
        daoB.insert(
            BookEntity(
                id = "b3", title = "کتاب تمام‌شده", filePath = file3.absolutePath,
                totalChapters = 10, dateAdded = "2026-01-01T00:00:00Z"
            )
        )
        prefs.setProfileName("")
        prefs.setAvatarPath("")
        rank.maxRank = 1
        rankPrefs.setString(RankService.SECRET_KEY, "")
        XpStore(rankPrefs).totalXp = 50
        // Fresh device: no fonts yet (otherwise apply would skip the
        // font as a duplicate instead of adding it).
        FontStore(ctx).list().forEach { FontStore(ctx).deleteFont(it.name) }
        File(ctx.filesDir, "avatars").deleteRecursively()
        val floorBeforeApply = prefs.getFinishedBooksRestored()

        val result = service(daoB).applyBackup(preview)
        assertEquals(1, result.fontsAdded)
        assertEquals(0, result.fontsSkipped)
        assertEquals(0, result.fontsFailed)
        assertTrue(result.avatarApplied)
        assertEquals("Aster", prefs.profileName.first())
        assertEquals(floorBeforeApply, result.finishedBefore)
        assertEquals(preview.finishedBooks, result.finishedAdded)
        assertEquals(floorBeforeApply + preview.finishedBooks, result.finishedTotal)
        assertEquals(result.finishedTotal, prefs.getFinishedBooksRestored())
        assertEquals(
            maxOf(result.rankBefore, preview.maxRank, RankService.rankIndexForStatic(result.finishedTotal)),
            result.rankAfter
        )
        assertTrue(rankPrefs.getString(RankService.SECRET_KEY, "").split(',').contains("testsecret"))
        assertEquals(150, XpStore(rankPrefs).totalXp)
        assertEquals(2, result.recordsApplied)
        assertEquals(0, result.recordsKept)

        val b1 = daoB.getById("b1")!!
        assertEquals(5, b1.lastChapterIndex)
        assertTrue(b1.isFavorite)
        assertEquals("خوانده‌شده", b1.collectionName)
        assertEquals("2026-02-01T00:00:00Z", b1.lastOpenedDate)
        val b3 = daoB.getById("b3")!!
        assertEquals(9, b3.lastChapterIndex)
        assertTrue(isCompleted(b3))
        // Private book untouched.
        assertEquals(0, daoB.getById("b2")!!.lastChapterIndex)

        assertEquals(1, FontStore(ctx).list().size)
        assertTrue(File(ctx.filesDir, "avatars/avatar.jpg").isFile)
        assertTrue(preview.payloadDir.listFiles()?.isEmpty() == true)
    }

    @Test
    fun reRestore_sumsFinishedBooks() = runBlocking {
        // The summing spec, pinned: restoring the same backup twice
        // adds its count twice (NOT idempotent — see applyBackup docs).
        val dao = newDao()
        val f = bookFile("sum-book.bin")
        dao.insert(
            BookEntity(
                id = "s1", title = "تمام‌شده", filePath = f.absolutePath,
                totalChapters = 4, lastChapterIndex = 3,
                dateAdded = "2026-01-01T00:00:00Z",
                lastOpenedDate = "2026-02-01T00:00:00Z"
            )
        )
        prefs.setProfileName("Sum")
        val bytes = exportBytes(dao)

        val daoB = newDao() // empty library: finished comes only from the floor
        val preview = expectOk(bytes, daoB)
        val first = service(daoB).applyBackup(preview)
        val second = service(daoB).applyBackup(expectOk(bytes, daoB))
        assertEquals(preview.finishedBooks, first.finishedAdded)
        assertEquals(first.finishedTotal + preview.finishedBooks, second.finishedTotal)
    }

    @Test
    fun privateBooks_excludedFromExport() = runBlocking {
        val dao = newDao()
        val f = bookFile("private-only.bin")
        dao.insert(
            BookEntity(
                id = "p1", title = "خصوصی تمام‌شده", filePath = f.absolutePath,
                totalChapters = 4, lastChapterIndex = 3,
                dateAdded = "2026-01-01T00:00:00Z",
                lastOpenedDate = "2026-02-01T00:00:00Z", isPrivate = true
            )
        )
        prefs.setProfileName("Priv")
        val floor = prefs.getFinishedBooksRestored()
        val preview = expectOk(exportBytes(dao), dao)
        // A finished PRIVATE book must not raise the count nor leak a record.
        assertEquals(floor, preview.finishedBooks)
        assertTrue(preview.records.isEmpty())
    }

    // --- tampering ---------------------------------------------------------

    /** A valid export carrying exactly one font payload, for tamper tests. */
    private fun exportWithFont(): ByteArray = runBlocking {
        prefs.setProfileName("T")
        addUserFont()
        exportBytes(newDao())
    }

    @Test
    fun tamperedFontBytes_rejectedCorrupt() {
        val bytes = exportWithFont()
        // Replace the font payload with garbage, manifest untouched:
        // the per-file SHA-256 check must catch it.
        val tampered = rewriteZip(bytes, mapOf("fonts/001.ttf" to "garbage-not-a-font".toByteArray()))
        expectFailed(tampered, BackupError.CORRUPT)
    }

    @Test
    fun tamperedIntegrity_rejectedCorrupt() {
        val bytes = exportWithFont()
        val tampered = rewriteZip(
            bytes,
            mapOf(
                "manifest.json" to JSONObject(String(readEntry(bytes, "manifest.json")))
                    .put("integrity", "0".repeat(64))
                    .toString(2).toByteArray(Charsets.UTF_8)
            )
        )
        expectFailed(tampered, BackupError.CORRUPT)
    }

    @Test
    fun extraEntry_rejectedCorrupt() {
        val bytes = exportWithFont()
        val tampered = rewriteZip(bytes, extra = mapOf("evil.txt" to "x".toByteArray()))
        expectFailed(tampered, BackupError.CORRUPT)
    }

    @Test
    fun missingEntry_rejectedCorrupt() {
        val bytes = exportWithFont()
        val tampered = rewriteZip(bytes, mapOf("fonts/001.ttf" to null))
        expectFailed(tampered, BackupError.CORRUPT)
    }

    @Test
    fun notAbackup_rejected() {
        // Not a zip at all.
        expectFailed("this is not a backup".toByteArray(), BackupError.NOT_A_BACKUP)
        // A zip, but no manifest entry.
        expectFailed(zipOf(mapOf("x.txt" to "y".toByteArray())), BackupError.NOT_A_BACKUP)
    }

    @Test
    fun tooNewVersion_rejected() {
        val m = JSONObject()
            .put("format", BackupService.FORMAT_ID)
            .put("formatVersion", 3)
        expectFailed(craftBackup(m, emptyMap()), BackupError.TOO_NEW)
    }

    @Test
    fun missingVersion_rejectedCorrupt() {
        val m = JSONObject().put("format", BackupService.FORMAT_ID)
        expectFailed(craftBackup(m, emptyMap()), BackupError.CORRUPT)
    }

    @Test
    fun wrongFormat_rejectedNotABackup() {
        val m = JSONObject()
            .put("format", "something-else")
            .put("formatVersion", 2)
        expectFailed(craftBackup(m, emptyMap()), BackupError.NOT_A_BACKUP)
    }

    @Test
    fun outOfRangeProfileValues_rejectedCorrupt() {
        // maxRank beyond the rank table.
        expectFailed(
            craftBackup(
                manifestJson(
                    profile = profileJson("T", 99, 0),
                    integrity = BackupService.computeIntegrity(emptyMap(), "T", 99, 0, emptyList())
                ),
                emptyMap()
            ),
            BackupError.CORRUPT
        )
        // Negative finished-books count.
        expectFailed(
            craftBackup(
                manifestJson(
                    profile = profileJson("T", 0, -1),
                    integrity = BackupService.computeIntegrity(emptyMap(), "T", 0, -1, emptyList())
                ),
                emptyMap()
            ),
            BackupError.CORRUPT
        )
        // Secret id with illegal characters.
        expectFailed(
            craftBackup(
                manifestJson(
                    profile = profileJson("T", 0, 0, secrets = listOf("bad id!")),
                    integrity = BackupService.computeIntegrity(emptyMap(), "T", 0, 0, listOf("bad id!"))
                ),
                emptyMap()
            ),
            BackupError.CORRUPT
        )
    }

    @Test
    fun invalidFontMagic_rejectedFontInvalid() {
        // Hashes recomputed to match the bad bytes: only the magic
        // check can still catch this.
        val bad = "PK\u0003\u0004not-a-font".toByteArray(Charsets.US_ASCII)
        val sha = BackupService.sha256Hex(bad)
        val fonts = JSONArray().put(
            JSONObject()
                .put("file", "fonts/001.ttf")
                .put("displayName", "Bad")
                .put("familyName", "Bad")
                .put("originalFileName", "bad.ttf")
                .put("sha256", sha)
                .put("bytes", bad.size)
        )
        val manifest = manifestJson(
            profile = profileJson("T", 0, 0),
            fonts = fonts,
            integrity = BackupService.computeIntegrity(mapOf("fonts/001.ttf" to sha), "T", 0, 0, emptyList())
        )
        expectFailed(craftBackup(manifest, mapOf("fonts/001.ttf" to bad)), BackupError.FONT_INVALID)
    }

    @Test
    fun invalidAvatarImage_rejectedImageInvalid() {
        val bad = "not-an-image".toByteArray(Charsets.UTF_8)
        val sha = BackupService.sha256Hex(bad)
        val avatar = JSONObject()
            .put("file", "avatar.png")
            .put("sha256", sha)
            .put("bytes", bad.size)
        val manifest = manifestJson(
            profile = profileJson("T", 0, 0),
            avatar = avatar,
            integrity = BackupService.computeIntegrity(mapOf("avatar.png" to sha), "T", 0, 0, emptyList())
        )
        expectFailed(craftBackup(manifest, mapOf("avatar.png" to bad)), BackupError.IMAGE_INVALID)
    }

    @Test
    fun oversizedDeclaredFont_rejectedTooLarge() {
        val sha = "a".repeat(64)
        val fonts = JSONArray().put(
            JSONObject()
                .put("file", "fonts/001.ttf")
                .put("displayName", "Big")
                .put("familyName", "Big")
                .put("originalFileName", "big.ttf")
                .put("sha256", sha)
                .put("bytes", BackupService.MAX_FONT_BYTES + 1)
        )
        val manifest = manifestJson(
            profile = profileJson("T", 0, 0),
            fonts = fonts,
            integrity = BackupService.computeIntegrity(mapOf("fonts/001.ttf" to sha), "T", 0, 0, emptyList())
        )
        // Declared size alone trips the cap, before any extraction.
        expectFailed(
            craftBackup(manifest, mapOf("fonts/001.ttf" to "tiny".toByteArray())),
            BackupError.TOO_LARGE
        )
    }

    @Test
    fun brokenRecordsJson_dropsRecordsNotBackup() {
        // Records are auxiliary: unparsable records.json (with correct
        // hash + integrity) yields Ok with no records, not a rejection.
        val bad = "{not json".toByteArray(Charsets.UTF_8)
        val sha = BackupService.sha256Hex(bad)
        val records = JSONObject()
            .put("file", "records.json")
            .put("sha256", sha)
            .put("bytes", bad.size)
        val manifest = manifestJson(
            profile = profileJson("T", 0, 0),
            records = records,
            integrity = BackupService.computeIntegrity(mapOf("records.json" to sha), "T", 0, 0, emptyList())
        )
        val preview = expectOk(craftBackup(manifest, mapOf("records.json" to bad)))
        assertTrue(preview.records.isEmpty())
    }

    @Test
    fun v1Backup_restoresWithoutRecords() = runBlocking {
        val manifest = manifestJson(
            version = 1,
            profile = profileJson("V1", 1, 2),
            integrity = BackupService.computeIntegrity(emptyMap(), "V1", 1, 2, emptyList())
        )
        val preview = expectOk(craftBackup(manifest, emptyMap()))
        assertEquals("V1", preview.profileName)
        assertEquals(1, preview.maxRank)
        assertEquals(2, preview.finishedBooks)
        assertTrue(preview.records.isEmpty())

        val dao = newDao()
        prefs.setProfileName("")
        val floorBefore = prefs.getFinishedBooksRestored()
        val result = service(dao).applyBackup(preview)
        assertEquals("V1", prefs.profileName.first())
        assertEquals(floorBefore, result.finishedBefore)
        assertEquals(2, result.finishedAdded)
        assertEquals(floorBefore + 2, result.finishedTotal)
    }

    private fun readEntry(zipBytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == name) return zin.readBytes()
            }
        }
        throw AssertionError("entry $name not found")
    }
}
