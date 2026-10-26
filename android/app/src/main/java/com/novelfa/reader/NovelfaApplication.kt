package com.novelfa.reader

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.novelfa.reader.data.CoverStore
import com.novelfa.reader.data.GeneratedCover
import com.novelfa.reader.data.MIGRATION_1_2
import com.novelfa.reader.data.ReaderDb
import com.novelfa.reader.data.RecommendedFonts
import com.novelfa.reader.data.isUuidLikeTitle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App-wide Coil [ImageLoaderFactory] (Task 9): Coil 2's default disk cache
 * can grow past our budget, so the cover loader is pinned to 100 MB under
 * `cacheDir/covers`. Library/Collections screens keep using
 * `coil.compose.AsyncImage` with the default loader — this factory supplies
 * that default. (Do not confuse with [NovelfaApp], the root `@Composable`.)
 *
 * Also owns the single [ReaderDb] instance for the whole app (NavGraph
 * destinations share it instead of each building their own) and prunes
 * orphaned `cacheDir/epub_open/<bookId>` extraction dirs on startup.
 */
class NovelfaApplication : Application(), ImageLoaderFactory {

    /** Single Room instance for the whole app. */
    val db: ReaderDb by lazy {
        Room.databaseBuilder(this, ReaderDb::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        pruneOrphanedEpubCache()
        // Bundled recommended fonts (B Koodak, Vazirmatn — WPF parity).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { RecommendedFonts.installIfMissing(this@NovelfaApplication) }
        }
        // Books imported before originalFileName existed show their UUID
        // storage name; recover a real name from the EPUB's own dc:title.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { backfillMissingBookNames() }
        }
    }

    /**
     * One-time repair for books imported before `originalFileName` was
     * stored (they show a UUID as their title): reads each such book's
     * EPUB `dc:title` directly from the zip (no full extraction) and stores
     * it as the original file name, so [displayTitle] shows something
     * meaningful. Books whose EPUB has no usable title are left alone.
     */
    private suspend fun backfillMissingBookNames() {
        val dao = db.bookDao()
        val books = dao.allBooks().first()
        for (book in books) {
            // Converted books (TXT/Markdown/DOCX/PDF) already HAVE an
            // originalFileName — the old backfill skipped them — yet
            // their stored title is still the storage UUID when the
            // document carried no internal title (the parser fell back
            // to the "$uuid.<ext>" file name). Repair those from the
            // original file name they already carry, and regenerate
            // their (UUID-titled) generated cover with the real title.
            // Idempotent: once the title is no longer UUID-like, this
            // branch never runs for that book again.
            if (book.originalFileName != null && isUuidLikeTitle(book.title)) {
                val clean = book.originalFileName.substringBeforeLast('.')
                    .takeIf { it.isNotBlank() && !isUuidLikeTitle(it) } ?: continue
                var coverPath = book.coverPath
                val ext = File(book.filePath).extension.lowercase()
                if (ext in setOf("txt", "md", "markdown", "pdf", "docx")) {
                    // Converted books carry no embedded art, so their
                    // cover is always the generated one — regenerate it
                    // with the real title. Best effort: on any failure
                    // the old cover path is kept, only the title fix
                    // (the actual bug) is guaranteed.
                    coverPath = runCatching {
                        val tmp = GeneratedCover.generate(
                            this@NovelfaApplication, clean, book.filePath
                        ) ?: return@runCatching coverPath
                        try {
                            // CoverStore.saveCover never overwrites an
                            // existing dest, so copy over it directly.
                            val dest = File(
                                File(filesDir, "covers"),
                                CoverStore.coverFileName(book.filePath, ".png")
                            ).apply { parentFile?.mkdirs() }
                            tmp.copyTo(dest, overwrite = true)
                            dest.absolutePath
                        } finally {
                            tmp.delete()
                        }
                    }.getOrNull() ?: coverPath
                }
                dao.update(book.copy(title = clean, coverPath = coverPath))
                continue
            }
            if (book.originalFileName != null) continue
            val epubFile = File(book.filePath)
            val epubTitle = readEpubDcTitle(epubFile) ?: continue
            val clean = epubTitle.trim().takeIf { it.isNotBlank() } ?: continue
            dao.update(
                book.copy(
                    originalFileName = "$clean.epub",
                    // If the stored title is itself the UUID (or blank), the
                    // EPUB title is strictly better.
                    title = if (book.title.isBlank() || isUuidLikeTitle(book.title)) clean else book.title
                )
            )
        }
    }

    /**
     * Reads an EPUB's `<dc:title>` without extracting the whole book:
     * opens the zip, finds the OPF via `META-INF/container.xml`, and pulls
     * the title with a regex. Returns null when anything is missing.
     */
    private fun readEpubDcTitle(epubFile: File): String? {
        if (!epubFile.isFile) return null
        return try {
            java.util.zip.ZipFile(epubFile).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
                val containerXml = zip.getInputStream(containerEntry)
                    .bufferedReader(Charsets.UTF_8).readText()
                val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']")
                    .find(containerXml)?.groupValues?.get(1) ?: return null
                val opfEntry = zip.getEntry(opfPath) ?: return null
                val opfXml = zip.getInputStream(opfEntry)
                    .bufferedReader(Charsets.UTF_8).readText()
                Regex(
                    "<dc:title[^>]*>(.*?)</dc:title>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                ).find(opfXml)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.replace("&amp;", "&")
                    ?.replace("&lt;", "<")
                    ?.replace("&gt;", ">")
                    ?.replace("&quot;", "\"")
                    ?.replace("&#39;", "'")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deletes `filesDir/epub_open/<bookId>` dirs whose book no longer exists
     * in the DB (e.g. the book was deleted while its extraction was cached),
     * plus the legacy `cacheDir/epub_open` orphans from before the cache
     * moved to filesDir, plus any leftover `cacheDir/epub_tmp/<id>` dirs
     * orphaned when the process died mid-import. ReaderScreen re-extracts
     * on demand via its stamp check, so this only drops dead weight —
     * never a live book's chapters.
     */
    private fun pruneOrphanedEpubCache() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val ids = db.bookDao().allBooks().first().map { it.id }.toSet()
                File(filesDir, "epub_open").listFiles()
                    ?.filter { it.isDirectory && it.name !in ids }
                    ?.forEach { it.deleteRecursively() }
                File(cacheDir, "epub_open").listFiles()
                    ?.filter { it.isDirectory && it.name !in ids }
                    ?.forEach { it.deleteRecursively() }
                // Importer deletes epub_tmp on success/cancel/failure, but a
                // process kill in the middle orphans it permanently.
                File(cacheDir, "epub_tmp").listFiles()
                    ?.filter { it.isDirectory }
                    ?.forEach { it.deleteRecursively() }
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
}
