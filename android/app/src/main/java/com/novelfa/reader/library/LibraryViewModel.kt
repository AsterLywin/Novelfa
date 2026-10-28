package com.novelfa.reader.library

import android.net.Uri
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PdfPasswordProvider
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.SortFa
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.data.isInProgress
import com.novelfa.reader.data.isUuidLikeTitle
import com.novelfa.reader.data.progressPercent
import com.novelfa.reader.data.sha256OfFile
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
        // A UUID title is never a real title (see isUuidLikeTitle):
        // it must fall back to the original file name, exactly like a
        // generic title does. The fallback chain also skips UUID-like
        // names at every step, so a book whose original name is itself
        // missing can never "fall back" to its own storage UUID.
        val display = if (duplicate || isGenericTitle(t) || isUuidLikeTitle(t)) {
            val original = book.originalFileName?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() && !isUuidLikeTitle(it) }
            if (original != null) {
                original
            } else if (book.filePath.isNotEmpty()) {
                val name = File(book.filePath).nameWithoutExtension
                if (name.isNotBlank() && !isUuidLikeTitle(name)) name else book.title
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
    filesDir: File? = null,
    // Stored as a property (it was a bare constructor parameter, usable
    // only in initializers): removeBook needs it to clear the deleted
    // book's per-book reader theme.
    private val prefs: PrefsStore? = null
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
        filesDir,
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

    /** One failed import in a batch: file name + human-readable reason. */
    data class FailedImport(val name: String, val reason: String)

    /** Batch import result (WPF parity: added/duplicates/failed summary). */
    data class ImportSummary(
        val added: Int,
        val duplicates: Int,
        val failed: Int,
        /** URIs that were skipped as duplicates (for overwrite confirmation). */
        val duplicateUris: List<Uri> = emptyList(),
        /** Display names of the duplicates (for the confirmation dialog). */
        val duplicateNames: List<String> = emptyList(),
        /** Lowercased original file names of the newly added books. */
        val addedNames: List<String> = emptyList(),
        /** Added books whose reading progress was restored from a record. */
        val progressRestored: Int = 0,
        /** Per-file failures (batch imports only; single-file rethrows). */
        val failedDetails: List<FailedImport> = emptyList()
    )

    /**
     * Imports several books, skipping ones already in the library and
     * ones repeated inside the batch. A single-URI batch rethrows so the
     * diagnostic import-error path keeps working.
     *
     * Duplicate detection is by CONTENT, not by name alone: the name
     * (or, when the name is unknown, the declared size) only nominates
     * candidates, and a streaming SHA-256 decides. So the same book
     * under a new name is still a duplicate, and an updated book under
     * the old name is no longer blocked as a "duplicate" — the old
     * name-only rule did both of those backwards. A hash that cannot
     * be computed on either side falls back to the old name rule
     * (treat as duplicate) rather than importing a possible copy.
     */
    suspend fun importMultiple(
        uris: List<Uri>,
        overwriteDuplicates: Boolean = false,
        passwordProvider: PdfPasswordProvider? = null,
        onStage: ((String?) -> Unit)? = null
    ): ImportSummary {
        val imp = requireNotNull(importer) { "Importer not provided" }
        val existingBooks = dao.allBooks().first()
        val knownByName = existingBooks
            .mapNotNull { b -> b.originalFileName?.lowercase()?.let { it to b } }
            .toMap()
        val known = knownByName.keys.toMutableSet()
        val batchAddedNames = mutableSetOf<String>()
        var added = 0
        var duplicates = 0
        var failed = 0
        var restored = 0
        val duplicateUris = mutableListOf<Uri>()
        val duplicateNames = mutableListOf<String>()
        val addedNames = mutableListOf<String>()
        val failedDetails = mutableListOf<FailedImport>()
        // Lazily hashed once per URI, only when a candidate exists:
        // hashing every pick unconditionally would re-read every book.
        val uriHashes = mutableMapOf<Uri, String?>()
        suspend fun hashOf(uri: Uri): String? {
            if (uri !in uriHashes) uriHashes[uri] = imp.sha256OfUri(uri)
            return uriHashes[uri]
        }
        suspend fun isSameContent(uri: Uri, book: BookEntity): Boolean? {
            val newHash = hashOf(uri) ?: return null
            val stored = File(book.filePath)
            if (!stored.isFile) return null
            val oldHash = runCatching { sha256OfFile(stored) }.getOrNull() ?: return null
            return newHash == oldHash
        }
        for (uri in uris) {
            val displayName = imp.displayNameOf(uri)
            val key = displayName?.lowercase()
            if (!overwriteDuplicates) {
                if (key != null && key in batchAddedNames) {
                    // Repeated inside this batch: the first pick already
                    // imported it; the name rule is enough here.
                    duplicates++
                    duplicateUris.add(uri)
                    duplicateNames.add(displayName)
                    continue
                }
                var duplicate: BookEntity? = null
                var undecided = false
                if (key != null && key in known) {
                    when (isSameContent(uri, knownByName.getValue(key))) {
                        true -> duplicate = knownByName.getValue(key)
                        false -> duplicate = null
                        null -> {
                            duplicate = knownByName.getValue(key)
                            undecided = true
                        }
                    }
                } else {
                    // No name match: nominate by declared size, then hash.
                    val size = imp.displaySizeOf(uri)
                    if (size != null) {
                        for (candidate in existingBooks) {
                            val stored = File(candidate.filePath)
                            if (stored.isFile && stored.length() == size) {
                                when (isSameContent(uri, candidate)) {
                                    true -> {
                                        duplicate = candidate
                                        break
                                    }
                                    false, null -> Unit
                                }
                            }
                        }
                    }
                }
                if (duplicate != null) {
                    duplicates++
                    // An undecided (unhashable) name collision keeps the
                    // old overwrite-confirmation path (uri + name both
                    // recorded); a proven same-content duplicate is
                    // skipped silently and needs neither.
                    if (undecided) {
                        duplicateUris.add(uri)
                        if (displayName != null) duplicateNames.add(displayName)
                    }
                    continue
                }
            }
            try {
                val newId = imp.importBook(
                    uri,
                    overwrite = overwriteDuplicates,
                    passwordProvider = passwordProvider,
                    onStage = onStage
                )
                added++
                if (imp.applyReadingRecord(newId)) restored++
                if (key != null) {
                    known.add(key)
                    batchAddedNames.add(key)
                    addedNames.add(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Single-file import keeps the old behavior: the caller shows
                // the diagnostic detail from the real exception.
                if (uris.size == 1) throw e
                failed++
                failedDetails += FailedImport(
                    displayName ?: uri.lastPathSegment ?: uri.toString(),
                    (e as? com.novelfa.reader.data.ImportException)?.messageFa
                        ?: e.message ?: e.javaClass.simpleName
                )
            }
        }
        return ImportSummary(
            added, duplicates, failed, duplicateUris, duplicateNames,
            addedNames, restored, failedDetails
        )
    }

    /**
     * Overwrites existing books with the given URIs: removes the old book
     * (by original file name match) then imports the new file.
     */
    suspend fun overwriteDuplicates(
        uris: List<Uri>,
        passwordProvider: PdfPasswordProvider? = null
    ): ImportSummary {
        val imp = requireNotNull(importer) { "Importer not provided" }
        var added = 0
        var failed = 0
        var restored = 0
        val failedDetails = mutableListOf<FailedImport>()
        for (uri in uris) {
            val displayName = imp.displayNameOf(uri)
            val key = displayName?.lowercase()
            try {
                // Remove the existing book with the same file name.
                if (key != null) {
                    val existing = dao.allBooks().first()
                        .firstOrNull { it.originalFileName?.lowercase() == key }
                    if (existing != null) {
                        removeBook(existing)
                    }
                }
                val newId = imp.importBook(uri, passwordProvider = passwordProvider)
                added++
                if (imp.applyReadingRecord(newId)) restored++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                failedDetails += FailedImport(
                    displayName ?: uri.lastPathSegment ?: uri.toString(),
                    (e as? com.novelfa.reader.data.ImportException)?.messageFa
                        ?: e.message ?: e.javaClass.simpleName
                )
            }
        }
        return ImportSummary(
            added, 0, failed, progressRestored = restored, failedDetails = failedDetails
        )
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

    suspend fun removeBook(book: BookEntity) {
        // Keep the reading record so re-importing this file later
        // restores its place; capture must run before the file goes.
        importer?.captureReadingRecord(book.id)
        collections.removeBook(book.id)
        // Per-book reader theme lived in prefs under readerTheme_<id> and
        // used to survive deletion forever (one orphaned key per book).
        // Blank = default theme, so clearing is just resetting it.
        runCatching { prefs?.setReaderTheme(book.id, "") }
    }

    /**
     * Deletes every private book (row, EPUB file, cover, chapter cache)
     * through the normal remove path. Reading records are kept, like any
     * other removal, so re-importing a file later restores its place.
     * Used by the forgot-PIN recovery: without the PIN the protected
     * content cannot be kept, so it is destroyed instead of bypassed.
     */
    suspend fun deleteAllPrivateBooks() {
        dao.allBooks().first().filter { it.isPrivate }.forEach { removeBook(it) }
    }

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
