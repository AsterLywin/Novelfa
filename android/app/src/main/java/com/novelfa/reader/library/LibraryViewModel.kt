package com.novelfa.reader.library

import android.net.Uri
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.SortFa
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.data.isInProgress
import com.novelfa.reader.data.progressPercent
import com.novelfa.reader.settings.CollectionManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Library tabs. Predicates mirror WPF Book.cs: IsCompleted / IsInProgress. */
enum class Tab {
    ALL,
    READING,
    COMPLETED,
    RECENT,
    FAVORITES,
    PRIVATE
}

/**
 * Library sort modes, mirroring the WPF sort dropdown (default/title,
 * recently added, last read, name A–Y, most progress). Pure function so it
 * stays unit-testable; applied in the UI layer after [applyFilter].
 */
enum class SortMode {
    DEFAULT,
    RECENT_ADDED,
    LAST_READ,
    NAME_AZ,
    MOST_PROGRESS
}

fun sortBooks(
    list: List<BookEntity>,
    all: List<BookEntity>,
    mode: SortMode
): List<BookEntity> = when (mode) {
    // DEFAULT preserves the filter's own order (e.g. RECENT stays
    // lastOpenedDate-desc); NAME_AZ is the explicit title sort.
    SortMode.DEFAULT -> list
    SortMode.NAME_AZ ->
        list.sortedWith { a, b -> SortFa.compare(displayTitle(a, all), displayTitle(b, all)) }
    SortMode.RECENT_ADDED -> list.sortedByDescending { it.dateAdded }
    SortMode.LAST_READ -> list.sortedByDescending { it.lastOpenedDate ?: "" }
    SortMode.MOST_PROGRESS -> list.sortedByDescending { progressPercent(it) }
}

/**
 * Display name rule ported from WPF LibraryService.AssignDisplayTitles:
 * books sharing one title (case-insensitive, trimmed) — or carrying a generic
 * title such as "unknown"/"untitled"/"کتاب ترجمه شده" — show the file name
 * (without extension) so duplicates stay distinguishable.
 */
fun displayTitle(book: BookEntity, all: List<BookEntity>): String {
    val t = book.title.trim()
    val duplicate = all.count { it.title.trim().equals(t, ignoreCase = true) } > 1
    if (duplicate || isGenericTitle(t)) {
        // Prefer the original import file name; the stored filePath is a UUID
        // name that means nothing to the user.
        val original = book.originalFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        if (original != null) return original
        if (book.filePath.isNotEmpty()) {
            val name = File(book.filePath).nameWithoutExtension
            if (name.isNotBlank()) return name
        }
    }
    return book.title
}

private fun isGenericTitle(title: String): Boolean {
    if (title.isBlank()) return true
    val t = title.trim().replace("‌", " ")
    return t == "کتاب ترجمه شده" ||
        t.equals("unknown", ignoreCase = true) ||
        t.equals("untitled", ignoreCase = true)
}

/**
 * Thin ViewModel over [BookDao] + [Importer].
 *
 * [importer] is nullable only so pure-JVM unit tests can construct this class
 * without an Android Context; production code (NavGraph) always passes a real
 * Importer and [import] delegates straight to it.
 */
class LibraryViewModel(
    val dao: BookDao,
    private val importer: Importer? = null,
    val pinGate: PinGate = NoPinGate,
    cacheDir: File? = null
) {
    val books: Flow<List<BookEntity>> = dao.allBooks()

    /** Task 8 collection/book ops (pure logic in CollectionManager). */
    val collections = CollectionManager(dao, cacheDir)

    fun filtered(tab: Tab, query: String): Flow<List<BookEntity>> =
        dao.allBooks().map { applyFilter(it, tab, query) }

    fun byCollection(name: String): Flow<List<BookEntity>> = dao.byCollection(name)

    suspend fun import(uri: Uri): String =
        requireNotNull(importer) { "Importer not provided" }.importEpub(uri)

    /** Batch import result (WPF parity: added/duplicates/failed summary). */
    data class ImportSummary(
        val added: Int,
        val duplicates: Int,
        val failed: Int
    )

    /**
     * Imports several EPUBs, skipping ones already in the library (matched by
     * original file name, like the desktop client's path-based dedupe) and
     * ones repeated inside the batch. A single-URI batch rethrows so the
     * diagnostic import-error path keeps working.
     */
    suspend fun importMultiple(uris: List<Uri>): ImportSummary {
        val imp = requireNotNull(importer) { "Importer not provided" }
        val known = dao.allBooks().first()
            .mapNotNull { it.originalFileName?.lowercase() }
            .toMutableSet()
        var added = 0
        var duplicates = 0
        var failed = 0
        for (uri in uris) {
            val key = imp.displayNameOf(uri)?.lowercase()
            if (key != null && (key in known)) {
                duplicates++
                continue
            }
            try {
                imp.importEpub(uri)
                added++
                if (key != null) known.add(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Single-file import keeps the old behavior: the caller shows
                // the diagnostic detail from the real exception.
                if (uris.size == 1) throw e
                failed++
            }
        }
        return ImportSummary(added, duplicates, failed)
    }

    suspend fun toggleFavorite(book: BookEntity) {
        dao.update(book.copy(isFavorite = !book.isFavorite))
    }

    suspend fun setCollection(book: BookEntity, name: String?) {
        dao.update(book.copy(collectionName = name?.takeIf { it.isNotBlank() }))
    }

    // Task 8 delegates (rename/delete collections, reset progress, remove
    // book incl. files, private toggle, collection membership).
    suspend fun renameCollection(old: String, new: String) =
        collections.renameCollection(old, new)

    suspend fun deleteCollection(name: String) = collections.deleteCollection(name)

    suspend fun allCollectionNames(): List<String> = collections.allCollectionNames()

    suspend fun resetProgress(book: BookEntity) = collections.resetProgress(book.id)

    suspend fun removeBook(book: BookEntity) = collections.removeBook(book.id)

    suspend fun togglePrivate(book: BookEntity) = collections.togglePrivate(book.id)

    suspend fun removeFromCollection(book: BookEntity) =
        collections.removeFromCollection(book.id)
}

internal fun applyFilter(
    all: List<BookEntity>,
    tab: Tab,
    query: String
): List<BookEntity> {
    var list = if (tab == Tab.PRIVATE) {
        all.filter { it.isPrivate }
    } else {
        val visible = all.filter { !it.isPrivate }
        when (tab) {
            Tab.READING -> visible.filter { isInProgress(it) }
            Tab.COMPLETED -> visible.filter { isCompleted(it) }
            Tab.FAVORITES -> visible.filter { it.isFavorite }
            Tab.RECENT -> visible
                .filter { !it.lastOpenedDate.isNullOrEmpty() }
                .sortedByDescending { it.lastOpenedDate }
            Tab.ALL, Tab.PRIVATE -> visible
        }
    }
    val q = query.trim()
    if (q.isNotEmpty()) {
        list = list.filter {
            it.title.contains(q, ignoreCase = true) ||
                displayTitle(it, all).contains(q, ignoreCase = true)
        }
    }
    // RECENT keeps lastOpenedDate-desc order (WPF default sort); other tabs
    // sort by the DisplayTitle rule with Persian natural comparison.
    return if (tab == Tab.RECENT) {
        list
    } else {
        list.sortedWith { a, b -> SortFa.compare(displayTitle(a, all), displayTitle(b, all)) }
    }
}
