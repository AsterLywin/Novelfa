package com.novelfa.reader.library

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.domain.Book
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.ImportException
import com.novelfa.reader.data.PdfPasswordProvider
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.SortFa
import com.novelfa.reader.domain.isCompleted
import com.novelfa.reader.domain.isInProgress
import com.novelfa.reader.data.isUuidLikeTitle
import com.novelfa.reader.domain.progressPercent
import com.novelfa.reader.settings.CollectionManager
import com.novelfa.reader.settings.ManualCollectionRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Batch-import parallelism cap (step 4): 2 in flight. SAF copies
 * and parses are IO-bound, so 2 roughly halves a multi-book batch;
 * more made flash + provider contention dominate on a phone, so
 * the cap is deliberate, not a placeholder. */
private const val BATCH_PARALLELISM = 2

/**
 * Stored books indexed by file size, for the duplicate screen: a
 * picked file only needs hashing against books of the same declared
 * size. Built once per batch instead of scanning the whole library
 * per file — the old scan was O(n·m) full-file hash reads on a bulk
 * import. Books with no stored file are left out: a missing file
 * can never be a content match.
 */
internal fun booksBySize(books: List<Book>): Map<Long, List<Book>> =
    books.mapNotNull { book ->
        val file = File(book.filePath)
        if (file.isFile) file.length() to book else null
    }.groupBy({ it.first }, { it.second })

/** Per-file outcome of one parallel batch import (step 4): async
 * bodies share nothing, so aggregation after the join needs no lock. */
private data class BatchImportOutcome(
    val index: Int,
    val uri: Uri,
    val key: String?,
    val restored: Boolean,
    val error: Exception?
)

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
    list: List<Book>,
    all: List<Book>,
    mode: SortMode,
    useFileName: Boolean = false
): List<Book> = when (mode) {
    // DEFAULT preserves the filter's own order (e.g. RECENT stays
    // lastOpenedDate-desc); NAME_AZ is the explicit title sort.
    SortMode.DEFAULT -> list
    SortMode.NAME_AZ -> {
        // Precompute titles once: displayTitle() is O(n) per book.
        val titles = displayTitleMap(all, useFileName)
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
 * Port of upstream LibraryService.CleanFileName (2026-11-01): the file
 * name as the library shows it — the "@NovelFa" tag (with any preceding
 * underscores/spaces) stripped, underscores turned into spaces,
 * repeated whitespace collapsed, and trailing '-', ' ' and '.'
 * trimmed. A blank result falls back to the input name, never to an
 * empty title.
 */
fun cleanFileName(name: String): String {
    if (name.isBlank()) return name
    var t = Regex("[_\\s]*@NovelFa", RegexOption.IGNORE_CASE).replace(name, "")
    t = t.replace('_', ' ')
    t = Regex("\\s{2,}").replace(t, " ")
    t = t.trim().trim('-', ' ', '.')
    return if (t.isEmpty()) name else t
}

/**
 * The book's file name for display (extension stripped), or null when
 * no usable name exists. The picker's original file name wins — it is
 * the name the user sees in their folder — and the stored file path
 * is the fallback, with UUID-like storage names rejected exactly like
 * the duplicate-title fallback below. The result is [cleanFileName]d.
 */
fun fileNameTitle(book: Book): String? {
    val original = book.originalFileName?.substringBeforeLast('.')
        ?.takeIf { it.isNotBlank() && !isUuidLikeTitle(it) }
    if (original != null) return cleanFileName(original)
    if (book.filePath.isNotEmpty()) {
        val name = File(book.filePath).nameWithoutExtension
        if (name.isNotBlank() && !isUuidLikeTitle(name)) return cleanFileName(name)
    }
    return null
}

/**
 * Precomputes display titles for a book list in O(n): the duplicate-title
 * check in [displayTitle] is O(n) per book, which becomes O(n² log n) inside
 * sort comparators. Compute once per list emission, then look up by id.
 *
 * With [useFileName] (the library file-name setting, default OFF here so
 * existing callers keep the old rule), every book with a usable file name
 * shows that cleaned name instead of its stored title, mirroring
 * upstream AssignDisplayTitles; books without one keep their title.
 */
fun displayTitleMap(all: List<Book>, useFileName: Boolean = false): Map<String, String> {
    // Count normalized titles once.
    val titleCounts = mutableMapOf<String, Int>()
    for (b in all) {
        val t = b.title.trim().lowercase()
        titleCounts[t] = (titleCounts[t] ?: 0) + 1
    }
    return all.associate { book ->
        val t = book.title.trim()
        if (useFileName) {
            val named = fileNameTitle(book)?.takeIf { it.isNotBlank() }
            if (named != null) return@associate book.id to named
        }
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

fun displayTitle(book: Book, all: List<Book>, useFileName: Boolean = false): String {
    // Legacy per-book path (kept for call sites without a precomputed map).
    // Prefer displayTitleMap for lists.
    return displayTitleMap(all, useFileName)[book.id] ?: book.title
}

private fun isGenericTitle(title: String): Boolean {
    if (title.isBlank()) return true
    val t = title.trim().replace("‌", " ")
    return t == "کتاب ترجمه شده" ||
        t.equals("unknown", ignoreCase = true) ||
        t.equals("untitled", ignoreCase = true)
}

/**
 * Thin ViewModel over [BookRepository] + [Importer].
 *
 * [importer] is nullable only so pure-JVM unit tests can construct this class
 * without an Android Context; production code (NavGraph) always passes a real
 * Importer and [import] delegates straight to it.
 */
class LibraryViewModel(
    val repo: BookRepository,
    private val importer: Importer? = null,
    val pinGate: PinGate = NoPinGate,
    cacheDir: File? = null,
    filesDir: File? = null,
    // Stored as a property (it was a bare constructor parameter, usable
    // only in initializers): removeBook needs it to clear the deleted
    // book's per-book reader theme.
    private val prefs: PrefsStore? = null
) : ViewModel() {
    val books: Flow<List<Book>> = repo.books()

    /** Library file-name titles (Settings switch; false when no prefs exist, e.g. unit tests). */
    val fileNameTitle: Flow<Boolean> = prefs?.fileNameTitle ?: flowOf(false)

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
        repo,
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

    fun filtered(tab: Tab, query: String): Flow<List<Book>> =
        combine(repo.books(), fileNameTitle) { list, useFileName ->
            applyFilter(list, tab, query, useFileName)
        }

    fun byCollection(name: String): Flow<List<Book>> = repo.booksInCollection(name)

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
        /**
         * Library id of the book each duplicate matches, parallel to
         * [duplicateUris] (null for a repeat inside the batch, which
         * matches no pre-existing book). The overwrite path removes
         * THIS book: matching by name alone would miss a duplicate
         * that was renamed (same content, new name) and import a
         * second copy instead of overwriting.
         */
        val duplicateBookIds: List<String?> = emptyList(),
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
    /**
     * Instant reading (خوندن درجا): open a file handed in from
     * outside the app (file manager, Telegram, share) straight into
     * the reader.
     *
     * ComicScreen model: the file is read where it lives — the import
     * registers it in place ([Importer.importBook] with
     * `forceInPlace`, head-only sniff, no full copy, no full hash)
     * and the reader opens it from the source URI. A copy only
     * happens as a fallback inside [Importer.importBook] (provider
     * refuses a persistable permission, encrypted PDF on file://)
     * or at first open inside the reader (working copy for formats
     * the direct probe cannot serve). Manual import is untouched:
     * it still goes through [importMultiple] with its dialog and
     * its overwrite question.
     *
     * A file already in the library opens as-is — never an overwrite
     * dialog on this path: the same name/size screen as
     * [importMultiple], hash-confirmed when a candidate exists.
     */
    sealed interface OpenExternalResult {
        data class Opened(val bookId: String) : OpenExternalResult
        // detail is the technical cause (ImportException.message),
        // shown small under the reason on the opening screen so a
        // phone test can name the exact failing step — the user
        // cannot send logcat, so the reason alone is not enough.
        data class Failed(val reason: String, val detail: String? = null) : OpenExternalResult
    }

    suspend fun openExternal(
        uri: Uri,
        passwordProvider: PdfPasswordProvider? = null
    ): OpenExternalResult = withContext(Dispatchers.IO) {
        val imp = requireNotNull(importer) { "Importer not provided" }
        // Duplicate screen (same as importMultiple, single file):
        // nominate by original name, else by declared size, and let
        // a streaming hash decide.
        val existingBooks = repo.books().first()
        val displayName = imp.displayNameOf(uri)
        val key = displayName?.lowercase()
        suspend fun isSameContent(uri: Uri, book: Book): Boolean? {
            val newHash = imp.sha256OfUri(uri) ?: return null
            // A persisted content hash answers without re-reading
            // the stored book at all.
            book.contentHash?.let { return newHash == it }
            val stored = File(book.filePath)
            if (!stored.isFile) return null
            val oldHash = imp.sha256OfStoredFile(stored) ?: return null
            return newHash == oldHash
        }
        var duplicate: Book? = null
        if (key != null) {
            val byName = existingBooks.firstOrNull {
                it.originalFileName?.lowercase() == key
            }
            if (byName != null) {
                when (isSameContent(uri, byName)) {
                    true -> duplicate = byName
                    false -> duplicate = null
                    // Unhashable: the name rule, as before.
                    null -> duplicate = byName
                }
            }
        }
        if (duplicate == null) {
            val size = imp.displaySizeOf(uri)
            if (size != null) {
                for (candidate in booksBySize(existingBooks)[size].orEmpty()) {
                    when (isSameContent(uri, candidate)) {
                        true -> { duplicate = candidate; break }
                        false, null -> Unit
                    }
                }
            }
        }
        if (duplicate != null) return@withContext OpenExternalResult.Opened(duplicate.id)
        try {
            val newId = imp.importBook(
                uri,
                passwordProvider = passwordProvider,
                forceInPlace = true
            )
            imp.applyReadingRecord(newId)
            OpenExternalResult.Opened(newId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            OpenExternalResult.Failed(e.messageFa, detail = e.message)
        } catch (e: Exception) {
            OpenExternalResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun importMultiple(
        uris: List<Uri>,
        overwriteDuplicates: Boolean = false,
        passwordProvider: PdfPasswordProvider? = null,
        onStage: ((String?) -> Unit)? = null,
        // Progress for the shared import dialog: (1-based file index,
        // original file name). Fired before the duplicate checks for
        // each file, so the dialog names the file it is actually on
        // instead of the previous file or none at all.
        onFile: ((Int, String?) -> Unit)? = null
    ): ImportSummary = withContext(Dispatchers.IO) {
        // The whole batch runs off the main thread: the callers
        // launch from a Compose scope (Main), and the duplicate
        // screen below does blocking SAF queries
        // (displayNameOf/displaySizeOf) plus a per-file candidate
        // scan — 138 picks stalled the main thread past the ANR
        // limit. The callbacks are already IO-safe: onStage is
        // invoked from importBook (Dispatchers.IO) today, onFile
        // only sets snapshot state (safe to write from any thread),
        // and the password prompt documents the same.
        val imp = requireNotNull(importer) { "Importer not provided" }
        val existingBooks = repo.books().first()
        val knownByName = existingBooks
            .mapNotNull { b -> b.originalFileName?.lowercase()?.let { it to b } }
            .toMap()
        val known = knownByName.keys.toMutableSet()
        // Size index for the duplicate screen (see booksBySize):
        // without it every new file scanned every stored book and
        // re-read same-size files for hashing — O(n·m) full-file
        // reads on a bulk import, on top of the main-thread stall.
        val bySize = booksBySize(existingBooks)
        val batchAddedNames = mutableSetOf<String>()
        var added = 0
        var duplicates = 0
        var failed = 0
        var restored = 0
        val duplicateUris = mutableListOf<Uri>()
        val duplicateNames = mutableListOf<String>()
        val duplicateBookIds = mutableListOf<String?>()
        val addedNames = mutableListOf<String>()
        val failedDetails = mutableListOf<FailedImport>()
        // Lazily hashed once per URI, only when a candidate exists:
        // hashing every pick unconditionally would re-read every book.
        val uriHashes = mutableMapOf<Uri, String?>()
        suspend fun hashOf(uri: Uri): String? {
            if (uri !in uriHashes) uriHashes[uri] = imp.sha256OfUri(uri)
            return uriHashes[uri]
        }
        suspend fun isSameContent(uri: Uri, book: Book): Boolean? {
            val newHash = hashOf(uri) ?: return null
            // Step 2: a persisted content hash answers without
            // re-reading the stored book at all — the old path
            // re-hashed every same-size candidate on every
            // import. This also covers in-place `content://`
            // books with no working copy yet, which the file
            // path below could never compare (null = undecided).
            book.contentHash?.let { return newHash == it }
            val stored = File(book.filePath)
            if (!stored.isFile) return null
            // Cancellable streaming hash (not the blocking sha256OfFile):
            // duplicate detection re-reads whole stored books, and a
            // Cancel pressed during that read used to run on to the
            // end regardless, exactly like the parse paths below.
            val oldHash = imp.sha256OfStoredFile(stored) ?: return null
            return newHash == oldHash
        }
        // Step 4, bounded parallel batch: imports run at most
        // BATCH_PARALLELISM at a time (chunked + async). SAF
        // copies and parses are IO-bound, so 2 in flight roughly
        // halves a multi-book batch. Duplicate screening stays
        // SEQUENTIAL per file (it reads shared snapshots and must
        // see every earlier file's decision), and all counters
        // are aggregated AFTER each chunk joins, so no shared
        // mutable state is ever touched from two coroutines at
        // once. A single file still takes the exact old path
        // (one chunk of one). Cancel propagates through
        // coroutineScope: a cancelled chunk cancels both
        // in-flight imports.
        coroutineScope {
            for (chunk in uris.withIndex().chunked(BATCH_PARALLELISM)) {
                // Sequential screen for the whole chunk first: a
                // same-name repeat inside one chunk must be caught
                // here, because the two imports would otherwise
                // both pass the screen before either updates
                // batchAddedNames.
                val chunkReserved = mutableSetOf<String>()
                val chunkNames = mutableMapOf<Int, String?>()
                val toImport = mutableListOf<Triple<Int, Uri, String?>>()
                for ((index, uri) in chunk) {
                    val displayName = imp.displayNameOf(uri)
                    onFile?.invoke(index + 1, displayName)
                    chunkNames[index] = displayName
                    val key = displayName?.lowercase()
                    var skip = false
                    if (!overwriteDuplicates) {
                        if (key != null && (key in batchAddedNames || key in chunkReserved)) {
                            // Repeated inside this batch: the first pick already
                            // imported it; the name rule is enough here.
                            duplicates++
                            // Recorded once per URI: the same URI picked
                            // twice would otherwise arm the dialog twice
                            // and overwrite itself twice.
                            if (uri !in duplicateUris) {
                                duplicateUris.add(uri)
                                duplicateNames.add(displayName)
                                // No pre-existing book: the first pick
                                // is the match, found by name on overwrite.
                                duplicateBookIds.add(null)
                            }
                            skip = true
                        } else {
                            var duplicate: Book? = null
                            if (key != null && key in known) {
                                when (isSameContent(uri, knownByName.getValue(key))) {
                                    true -> duplicate = knownByName.getValue(key)
                                    false -> duplicate = null
                                    null -> duplicate = knownByName.getValue(key)
                                }
                            } else {
                                // No name match: nominate by declared
                                // size, then hash. The size index
                                // answers in O(1); the old code scanned
                                // every stored book per file.
                                val size = imp.displaySizeOf(uri)
                                if (size != null) {
                                    for (candidate in bySize[size].orEmpty()) {
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
                            if (duplicate != null) {
                                duplicates++
                                // EVERY skipped duplicate is recorded for
                                // the overwrite-confirmation dialog — proven
                                // same-content matches included. The old code
                                // recorded only undecided (unhashable)
                                // collisions, so re-importing a book already
                                // in the library skipped it silently and the
                                // dialog's arming condition (duplicates > 0
                                // AND duplicateUris non-empty) never fired:
                                // the dialog never appeared.
                                if (uri !in duplicateUris) {
                                    duplicateUris.add(uri)
                                    duplicateNames.add(
                                        displayName ?: uri.lastPathSegment ?: uri.toString()
                                    )
                                    duplicateBookIds.add(duplicate.id)
                                }
                                skip = true
                            }
                        }
                    }
                    if (!skip) {
                        if (key != null) chunkReserved.add(key)
                        toImport.add(Triple(index, uri, key))
                    }
                }
                // Parallel import of the screened chunk. The
                // per-file result carries everything aggregation
                // needs, so the async bodies share nothing.
                val results = toImport.map { (index, uri, key) ->
                    async {
                        val startedMs = SystemClock.elapsedRealtime()
                        try {
                            val newId = imp.importBook(
                                uri,
                                overwrite = overwriteDuplicates,
                                passwordProvider = passwordProvider,
                                onStage = onStage
                            )
                            val rec = imp.applyReadingRecord(newId)
                            Log.d(
                                "NovelfaImport",
                                "batch file ${index + 1} ms=${SystemClock.elapsedRealtime() - startedMs}"
                            )
                            BatchImportOutcome(index, uri, key, rec, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Single-file import keeps the old behavior: the caller shows
                            // the diagnostic detail from the real exception.
                            if (uris.size == 1) throw e
                            BatchImportOutcome(index, uri, key, false, e)
                        }
                    }
                }.awaitAll()
                // Sequential aggregation: the only place shared
                // counters change, so no Mutex is needed.
                for (r in results) {
                    if (r.error == null) {
                        added++
                        if (r.restored) restored++
                        if (r.key != null) {
                            known.add(r.key)
                            batchAddedNames.add(r.key)
                            addedNames.add(r.key)
                        }
                    } else {
                        failed++
                        failedDetails += FailedImport(
                            chunkNames[r.index] ?: r.uri.lastPathSegment ?: r.uri.toString(),
                            (r.error as? com.novelfa.reader.data.ImportException)?.messageFa
                                ?: r.error.message ?: r.error.javaClass.simpleName
                        )
                    }
                }
            }
        }
        ImportSummary(
            added, duplicates, failed, duplicateUris, duplicateNames,
            duplicateBookIds, addedNames, restored, failedDetails
        )
    }

    /**
     * Overwrites existing books with the given URIs: removes the old book
     * then imports the new file. [existingIds] is parallel to [uris]
     * (see [ImportSummary.duplicateBookIds]): a non-null id removes
     * THAT book, which is the only correct match for a duplicate found
     * by content under a new name; a null id falls back to the old
     * same-name match (in-batch repeats and undecided collisions).
     * Deliberately SEQUENTIAL, unlike importMultiple's step-4
     * parallelism: remove-then-import for the same name from two
     * coroutines would race (both remove, both import, or one
     * removes the other's fresh row).
     */
    suspend fun overwriteDuplicates(
        uris: List<Uri>,
        existingIds: List<String?> = emptyList(),
        passwordProvider: PdfPasswordProvider? = null,
        onStage: ((String?) -> Unit)? = null,
        onFile: ((Int, String?) -> Unit)? = null
    ): ImportSummary = withContext(Dispatchers.IO) {
        // Same rule as importMultiple: the whole batch runs off
        // the main thread (blocking SAF queries per file), and the
        // library is snapshotted ONCE — the old code re-loaded the
        // whole table for every overwritten file.
        val imp = requireNotNull(importer) { "Importer not provided" }
        val snapshot = repo.books().first()
        var added = 0
        var failed = 0
        var restored = 0
        val addedNames = mutableListOf<String>()
        val failedDetails = mutableListOf<FailedImport>()
        for ((index, uri) in uris.withIndex()) {
            val displayName = imp.displayNameOf(uri)
            onFile?.invoke(index + 1, displayName)
            val key = displayName?.lowercase()
            try {
                // Remove the matched book: by id when the duplicate
                // screen identified one, else by same file name.
                val existingId = existingIds.getOrNull(index)
                val existing = if (existingId != null) {
                    snapshot.firstOrNull { it.id == existingId }
                } else if (key != null) {
                    snapshot.firstOrNull { it.originalFileName?.lowercase() == key }
                } else null
                if (existing != null) {
                    removeBook(existing)
                }
                val newId = imp.importBook(
                    uri, passwordProvider = passwordProvider, onStage = onStage
                )
                added++
                if (key != null) addedNames.add(key)
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
        ImportSummary(
            added, 0, failed, progressRestored = restored,
            failedDetails = failedDetails, addedNames = addedNames
        )
    }

    suspend fun toggleFavorite(book: Book) {
        repo.update(book.copy(isFavorite = !book.isFavorite))
    }

    suspend fun setCollection(book: Book, name: String?) {
        repo.update(book.copy(collectionName = name?.takeIf { it.isNotBlank() }))
    }

    // Task 8 delegates (rename/delete collections, reset progress, remove
    // book incl. files, private toggle, collection membership).
    suspend fun renameCollection(old: String, new: String) =
        collections.renameCollection(old, new)

    suspend fun deleteCollection(name: String) = collections.deleteCollection(name)

    suspend fun createCollection(name: String) = collections.createCollection(name)

    suspend fun resetProgress(book: Book) {
        collections.resetProgress(book.id)
        // The vertical in-section position is progress too: without
        // this, a reset book would reopen at its old (chapter,
        // fraction) — the section position outranks lastChapterIndex
        // on open — instead of chapter 0's top.
        importer?.clearSectionPosition(book.id)
    }

    suspend fun removeBook(book: Book) {
        // Phase 2 (DB-01): a private book's data must never
        // survive its deletion into a later backup. Purge its
        // orphan records and remember its content hashes in the
        // confidential private list BEFORE the row goes (both
        // need the row/file to compute their keys). A public
        // book keeps the old behaviour: capture its record so
        // re-importing the file later restores its place.
        if (book.isPrivate) {
            importer?.purgeReadingRecord(book.id)
            importer?.privateContentHashes(book.id)?.forEach { hash ->
                runCatching { prefs?.addPrivateContentHash(hash) }
            }
        } else {
            // Keep the reading record so re-importing this file later
            // restores its place; capture must run before the file goes.
            importer?.captureReadingRecord(book.id)
        }
        collections.removeBook(book.id)
        // The persistable permission of a `content://` in-place
        // book dies with the book (see InPlaceSource); the file
        // itself is never touched — that is CollectionManager's
        // guard, not this call.
        importer?.releaseInPlacePermission(book.sourceUri)
        // Phase 2 (DB-03): per-book reader state dies with the
        // book, public or private — extras (bookmarks, scroll,
        // highlights, zoom, crops) and the per-book reader theme
        // key, actually removed, not just blanked.
        importer?.clearBookExtras(book.id)
        runCatching { prefs?.removeReaderTheme(book.id) }
    }

    /**
     * Re-points an in-place book at a new location of the same
     * file (moved outside the app, or a lost permission). The
     * book keeps its id, progress, and reading record — only its
     * source changes, and its caches are dropped so the next
     * open re-parses from the new location.
     */
    suspend fun relinkBook(bookId: String, uri: Uri): Boolean =
        importer?.relinkBook(bookId, uri) ?: false

    /**
     * Deletes every private book (row, EPUB file, cover, chapter cache)
     * through the normal remove path. Phase 2 (DB-01): unlike a
     * public removal, a private book's reading records are PURGED
     * (not kept) and its content hashes join the confidential
     * private list, so nothing identifiable survives into a later
     * backup. Used by the forgot-PIN recovery: without the PIN the
     * protected content cannot be kept, so it is destroyed instead
     * of bypassed.
     */
    suspend fun deleteAllPrivateBooks() {
        repo.books().first().filter { it.isPrivate }.forEach { removeBook(it) }
    }

    /**
     * Phase 2 (DB-01): privatizing a book purges its orphan
     * records — a record captured while the book was public
     * must not survive into a backup after the book went
     * private. The purge runs after the flip (the row still
     * exists, so its keys can be computed).
     */
    suspend fun togglePrivate(book: Book) {
        val wasPrivate = repo.book(book.id)?.isPrivate ?: book.isPrivate
        collections.togglePrivate(book.id)
        if (!wasPrivate) importer?.purgeReadingRecord(book.id)
    }

    suspend fun removeFromCollection(book: Book) =
        collections.removeFromCollection(book.id)
}

internal fun applyFilter(
    all: List<Book>,
    tab: Tab,
    query: String,
    useFileName: Boolean = false
): List<Book> {
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
                displayTitle(it, all, useFileName).contains(q, ignoreCase = true)
        }
    }
    // RECENT keeps lastOpenedDate-desc order (WPF default sort); other tabs
    // sort by the DisplayTitle rule with Persian natural comparison.
    return if (tab == Tab.RECENT) {
        list
    } else {
        list.sortedWith { a, b ->
            SortFa.compare(
                displayTitle(a, all, useFileName),
                displayTitle(b, all, useFileName)
            )
        }
    }
}

/**
 * Factory for entry-scoped [LibraryViewModel] instances (see NavGraph):
 * the ViewModel survives configuration changes and stays alive while
 * its destination is on the back stack, instead of being rebuilt by
 * remember. The instance is cleared with its back-stack entry, which
 * also bounds the Importer's hold on the creating Context (kept
 * deliberately: import error strings resolve in the app's locale).
 */
class LibraryViewModelFactory(
    private val repo: BookRepository,
    private val importer: Importer,
    private val pinGate: PinGate,
    private val cacheDir: File?,
    private val filesDir: File?,
    private val prefs: PrefsStore?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return LibraryViewModel(repo, importer, pinGate, cacheDir, filesDir, prefs) as T
    }
}
