package com.novelfa.reader

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.novelfa.reader.data.ReaderDb
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
        Room.databaseBuilder(this, ReaderDb::class.java, "reader.db").build()
    }

    override fun onCreate() {
        super.onCreate()
        pruneOrphanedEpubCache()
    }

    /**
     * Deletes `cacheDir/epub_open/<bookId>` dirs whose book no longer exists
     * in the DB (e.g. the book was deleted while its extraction was cached).
     * ReaderScreen re-extracts on demand via its stamp check, so this only
     * drops dead weight — never a live book's chapters.
     */
    private fun pruneOrphanedEpubCache() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val ids = db.bookDao().allBooks().first().map { it.id }.toSet()
                File(cacheDir, "epub_open").listFiles()
                    ?.filter { it.isDirectory && it.name !in ids }
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
