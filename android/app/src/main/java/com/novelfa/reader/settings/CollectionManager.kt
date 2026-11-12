package com.novelfa.reader.settings

import com.novelfa.reader.R
import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.data.SortFa
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Thrown for collection validation failures; [resId] is the localized
 * user-facing message (same pattern as PinException).
 */
class CollectionException(val resId: Int) : IllegalArgumentException()

/**
 * Upstream CollectionRegistry parity: persistence for manually-created
 * collection names (including empty ones).
 */
interface ManualCollectionRegistry {
    suspend fun get(): Set<String>
    suspend fun add(name: String)
    suspend fun remove(name: String)
    suspend fun rename(old: String, new: String)
}

/**
 * Collection + book-removal ops ported from LibraryService.cs
 * (RenameCollection, RemoveFromCollection/SetCollection, ResetProgress,
 * RemoveBook + CleanupUnusedCovers minimally, TogglePrivate).
 *
 * Pure logic over [BookRepository] (suspend, no Android APIs) so it is unit-testable
 * with a fake repo. LibraryViewModel delegates to this class.
 */
class CollectionManager(
    private val repo: BookRepository,
    private val cacheDir: File? = null,
    private val filesDir: File? = null,
    private val manualRegistry: ManualCollectionRegistry? = null
) {

    /** Create a manually-named collection (upstream NameInputWindow flow). */
    suspend fun createCollection(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) throw CollectionException(R.string.collection_name_blank)
        if (clean.length > 60) throw CollectionException(R.string.collection_name_too_long)
        if (allCollectionNames().any { it.equals(clean, ignoreCase = true) }) {
            throw CollectionException(R.string.collection_name_exists)
        }
        manualRegistry?.add(clean)
    }

    companion object {
        /**
         * The 5 ready-made status collections (upstream
         * CollectionRegistry.Presets, created once via
         * EnsurePresets): (fa, en) data pairs, in the upstream
         * order. The en "Reading" fills a gap upstream leaves
         * untranslated (LocStrings has no «در حال خواندن» entry).
         */
        val STATUS_PRESETS = listOf(
            "بعداً می‌خونم" to "Read later",
            "در حال خواندن" to "Reading",
            "نگه داشتم" to "On hold",
            "رها کردم" to "Dropped",
            "تمام شد" to "Finished"
        )

        /** All preset names in either language (a preset seeded in fa stays findable under en UI). */
        val STATUS_PRESET_NAMES: Set<String> =
            STATUS_PRESETS.flatMap { listOf(it.first, it.second) }.toSet()
    }

    /**
     * Create the status presets that do not exist yet (upstream
     * EnsurePresets): never throws, never re-creates a preset the
     * user deleted — the caller sets the seeded flag after this
     * returns, so a deleted preset stays deleted.
     */
    suspend fun ensureStatusPresets(names: List<String>) {
        val existing = allCollectionNames()
        for (name in names) {
            val clean = name.trim()
            if (clean.isEmpty()) continue
            if (existing.any { it.equals(clean, ignoreCase = true) }) continue
            runCatching { manualRegistry?.add(clean) }
        }
    }

    /** Rename a collection: updates every book carrying [old] (blank -> throw). */
    suspend fun renameCollection(old: String, new: String) {
        val clean = new.trim()
        if (clean.isEmpty()) throw CollectionException(R.string.collection_name_blank)
        val existing = allCollectionNames()
        // Renaming onto an existing collection would silently merge the two
        // with no way back — refuse instead.
        if (existing.any { it.equals(clean, ignoreCase = true) && !it.equals(old, ignoreCase = true) }) {
            throw CollectionException(R.string.collection_name_exists)
        }
        repo.books().first()
            .filter { it.collectionName == old }
            .forEach { repo.update(it.copy(collectionName = clean)) }
        manualRegistry?.rename(old, clean)
    }

    /** Delete a collection: nulls out the name on every member book (books stay). */
    suspend fun deleteCollection(name: String) {
        repo.books().first()
            .filter { it.collectionName == name }
            .forEach { repo.update(it.copy(collectionName = null)) }
        manualRegistry?.remove(name)
    }

    /** Distinct non-blank collection names incl. empty manual ones. */
    suspend fun allCollectionNames(): List<String> {
        val fromBooks = repo.books().first()
            .mapNotNull { it.collectionName?.takeIf { n -> n.isNotBlank() } }
        val manual = manualRegistry?.get() ?: emptySet()
        // Persian natural order like the book list (the default sort used
        // to order Persian names by code point, unlike every book sort).
        return (fromBooks + manual).distinct().sortedWith { a, b -> SortFa.compare(a, b) }
    }

    /**
     * Port of ResetProgress: index back to 0 (LastChapterDisplay = index+1, so
     * back to 1) and lastOpenedDate cleared so progressPercent reports 0
     * ("not started"). WPF stores "" — Kotlin stores null, which
     * progressPercent/isCompleted treat identically (isNullOrEmpty).
     */
    suspend fun resetProgress(id: String) {
        val book = repo.book(id) ?: return
        // Reset also clears the text-point anchor: it belongs to the
        // old chapter, and reopening at chapter 0 must start at its
        // top, not at a stale li:tok from another chapter.
        repo.update(book.copy(lastChapterIndex = 0, lastOpenedDate = null, lastAnchor = null))
    }

    /**
     * Port of RemoveBook (+ CleanupUnusedCovers minimally): deletes the row,
     * the EPUB file, and the cover file when no remaining book references it.
     * (WPF keeps the file on disk; mobile deletes it to free app storage —
     * the confirm dialog text reflects this.)
     */
    suspend fun removeBook(id: String) {
        val book = repo.book(id) ?: return
        repo.delete(book)
        // In-place safety rule: when the book's filePath IS the
        // user's own file (in-place, picked by path), deleting the
        // book must NEVER delete that file. Every other shape's
        // filePath is app-owned — a stored copy, or a transient
        // working copy in cacheDir — and is deleted as before.
        // (The persistable permission for `content://` in-place
        // books is released by the ViewModel, which owns the
        // Context; this class is deliberately Context-free.)
        if (!repo.isExternalFile(book)) {
            runCatching { File(book.filePath).takeIf { it.isFile }?.delete() }
        }
        // The transient working copy of a `content://` in-place
        // book is cache, not the book itself — it goes with the
        // book, and the next import of the same source rebuilds it.
        cacheDir?.let { dir ->
            runCatching {
                com.novelfa.reader.data.InPlaceSource.workingDir(dir, id)
                    .takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
        // Drop this book's extracted chapter cache (ReaderScreen re-extracts on
        // demand; the startup prune in NovelfaApplication is the backstop).
        // The cache lives in filesDir now; the cacheDir path is the legacy
        // location, still cleaned for books cached before the move.
        filesDir?.let { dir ->
            runCatching {
                File(dir, "epub_open" + File.separatorChar + id)
                    .takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
        cacheDir?.let { dir ->
            runCatching {
                File(dir, "epub_open" + File.separatorChar + id)
                    .takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
        val cover = book.coverPath?.takeIf { it.isNotBlank() } ?: return
        val stillUsed = repo.books().first().any { it.coverPath == cover }
        if (!stillUsed) {
            runCatching { File(cover).takeIf { it.isFile }?.delete() }
        }
    }

    /** Port of TogglePrivate. */
    suspend fun togglePrivate(id: String) {
        val book = repo.book(id) ?: return
        repo.update(book.copy(isPrivate = !book.isPrivate))
    }

    /** Remove a single book from its collection (port of RemoveFromCollection). */
    suspend fun removeFromCollection(id: String) {
        val book = repo.book(id) ?: return
        repo.update(book.copy(collectionName = null))
    }
}
