package com.novelfa.reader.library

import android.net.Uri
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.SortFa
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.data.isInProgress
import com.novelfa.reader.data.progressPercent
import com.novelfa.reader.settings.CollectionManager
import com.novelfa.reader.settings.ManualCollectionRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    SortMode.NAME_AZ -> {
        // Precompute titles once: displayTitle() is O(n) per book.
        val titles = displayTitleMap(all)
        list.sortedWith { a, b ->
            SortFa.compare(titles[a.id] ?: a.title, titles[b.id] ?: b.title)
        }
    }
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
/**
 * Precomputes display titles for a book list in O(n): the duplicate-title
 * check in [displayTitle] is O(n) per book, which becomes O(n² log n) inside
 * sort comparators. Compute once per list emission, then look up by id.
 */
fun displayTitleMap(all: List<BookEntity>): Map<String, String> {
    // Count normalized titles once.
    val titleCounts = mutableMapOf<String, Int>()
    for (b in all) {
        val t = b.title.trim().lowercase()
        titleCounts[t] = (titleCounts[t] ?: 0) + 1
    }
    return all.associate { book ->
        val t = book.title.trim()
        val duplicate = (titleCounts[t.lowercase()] ?: 0) > 1
        val display = if (duplicate || isGenericTitle(t)) {
            val original = book.originalFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            if (original != null) {
                original
            } else if (book.filePath.isNotEmpty()) {
                val name = File(book.filePath).nameWithoutExtension
                if (name.isNotBlank()) name else book.title
            } else {
                book.title
            }
        } else {
            book.title
        }
        book.id to display
    }
}

fun displayTitle(book: BookEntity, all: List<BookEntity>): String {
    // Legacy per-book path (kept for call sites without a precomputed map).
    // Prefer displayTitleMap for lists.
    return displayTitleMap(all)[book.id] ?: book.title
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
    cacheDir: File? = null,
    prefs: PrefsStore? = null
) {
    val books: Flow<List<BookEntity>> = dao.allBooks()

    /**
     * All collection names incl. empty manual ones (upstream
     * GetAllCollectionNames + CollectionRegistry parity).
     */
    val collectionNames: Flow<List<String>> =
        combine(books, prefs?.manualCollections ?: flowOf(emptySet())) { list, manual ->
            (list.mapNotNull { it.collectionName?.takeIf { n -> n.isNotBlank() } } + manual)
                .distinct().sorted()
        }

    /** Task 8 collection/book ops (pure logic in CollectionManager). */
    val collections = CollectionManager(
        dao,
        cacheDir,
        prefs?.let { p ->
            object : ManualCollectionRegistry {
                override suspend fun get() = p.getManualCollections()
                override suspend fun add(name: String) = p.addManualCollection(name)
                override suspend fun remove(name: String) = p.removeManualCollection(name)
                override suspend fun rename(old: String, new: String) = p.renameManualCollection(old, new)
            }
        }
    )

    fun filtered(tab: Tab, query: String): Flow<List<BookEntity>> =
        dao.allBooks().map { applyFilter(it, tab, query) }

    fun byCollection(name: String): Flow<List<BookEntity>> = dao.byCollection(name)

    /** Batch import result (WPF parity: added/duplicates/failed summary). */
    data class ImportSummary(
        val added: Int,
        val duplicates: Int,
        val failed: Int,
        /** URIs that were skipped as duplicates (for overwrite confirmation). */
        val duplicateUris: List<Uri> = emptyList(),
        /** Display names of the duplicates (for the confirmation dialog). */
        val duplicateNames: List<String> = emptyList()
    )

    /**
     * Imports several EPUBs, skipping ones already in the library (matched by
     * original file name, like the desktop client's path-based dedupe) and
     * ones repeated inside the batch. A single-URI batch rethrows so the
     * diagnostic import-error path keeps working.
     */
    suspend fun importMultiple(
        uris: List<Uri>,
        overwriteDuplicates: Boolean = false
    ): ImportSummary {
        val imp = requireNotNull(importer) { "Importer not provided" }
        val known = dao.allBooks().first()
            .mapNotNull { it.originalFileName?.lowercase() }
            .toMutableSet()
        var added = 0
        var duplicates = 0
        var failed = 0
        val duplicateUris = mutableListOf<Uri>()
        val duplicateNames = mutableListOf<String>()
        for (uri in uris) {
            val displayName = imp.displayNameOf(uri)
            val key = displayName?.lowercase()
            if (!overwriteDuplicates && key != null && (key in known)) {
                duplicates++
                duplicateUris.add(uri)
                if (displayName != null) duplicateNames.add(displayName)
                continue
            }
            try {
                imp.importEpub(uri, overwrite = overwriteDuplicates)
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
        return ImportSummary(added, duplicates, failed, duplicateUris, duplicateNames)
    }

    /**
     * Overwrites existing books with the given URIs: removes the old book
     * (by original file name match) then imports the new file.
     */
    suspend fun overwriteDuplicates(uris: List<Uri>): ImportSummary {
        val imp = requireNotNull(importer) { "Importer not provided" }
        var added = 0
        var failed = 0
        for (uri in uris) {
            val displayName = imp.displayNameOf(uri)?.lowercase()
            try {
                // Remove the existing book with the same file name.
                if (displayName != null) {
                    val existing = dao.allBooks().first()
                        .firstOrNull { it.originalFileName?.lowercase() == displayName }
                    if (existing != null) {
                        removeBook(existing)
                    }
                }
                imp.importEpub(uri)
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        return ImportSummary(added, 0, failed)
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

    suspend fun createCollection(name: String) = collections.createCollection(name)

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
