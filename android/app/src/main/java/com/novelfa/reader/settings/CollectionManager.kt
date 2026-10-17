package com.novelfa.reader.settings

import com.novelfa.reader.data.BookDao
import java.io.File
import kotlinx.coroutines.flow.first

/** Thrown for collection validation failures; message is user-facing fa text. */
class CollectionException(messageFa: String) : IllegalArgumentException(messageFa)

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
 * Pure logic over [BookDao] (suspend, no Android APIs) so it is unit-testable
 * with a fake dao. LibraryViewModel delegates to this class.
 */
class CollectionManager(
    private val dao: BookDao,
    private val cacheDir: File? = null,
    private val manualRegistry: ManualCollectionRegistry? = null
) {

    /** Create a manually-named collection (upstream NameInputWindow flow). */
    suspend fun createCollection(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) throw CollectionException("اسم مجموعه نمی‌تونه خالی باشه.")
        if (clean.length > 60) throw CollectionException("اسم مجموعه حداکثر ۶۰ کاراکتره.")
        if (allCollectionNames().any { it.equals(clean, ignoreCase = true) }) {
            throw CollectionException("مجموعه‌ای با این اسم از قبل وجود داره.")
        }
        manualRegistry?.add(clean)
    }

    /** Rename a collection: updates every book carrying [old] (blank -> throw). */
    suspend fun renameCollection(old: String, new: String) {
        val clean = new.trim()
        if (clean.isEmpty()) throw CollectionException("اسم مجموعه نمی‌تونه خالی باشه.")
        val existing = allCollectionNames()
        // Renaming onto an existing collection would silently merge the two
        // with no way back — refuse instead.
        if (existing.any { it.equals(clean, ignoreCase = true) && !it.equals(old, ignoreCase = true) }) {
            throw CollectionException("مجموعه‌ای با این اسم از قبل وجود داره.")
        }
        dao.allBooks().first()
            .filter { it.collectionName == old }
            .forEach { dao.update(it.copy(collectionName = clean)) }
        manualRegistry?.rename(old, clean)
    }

    /** Delete a collection: nulls out the name on every member book (books stay). */
    suspend fun deleteCollection(name: String) {
        dao.allBooks().first()
            .filter { it.collectionName == name }
            .forEach { dao.update(it.copy(collectionName = null)) }
        manualRegistry?.remove(name)
    }

    /** Distinct non-blank collection names incl. empty manual ones. */
    suspend fun allCollectionNames(): List<String> {
        val fromBooks = dao.allBooks().first()
            .mapNotNull { it.collectionName?.takeIf { n -> n.isNotBlank() } }
        val manual = manualRegistry?.get() ?: emptySet()
        return (fromBooks + manual).distinct().sorted()
    }

    /**
     * Port of ResetProgress: index back to 0 (LastChapterDisplay = index+1, so
     * back to 1) and lastOpenedDate cleared so progressPercent reports 0
     * ("not started"). WPF stores "" — Kotlin stores null, which
     * progressPercent/isCompleted treat identically (isNullOrEmpty).
     */
    suspend fun resetProgress(id: String) {
        val book = dao.getById(id) ?: return
        dao.update(book.copy(lastChapterIndex = 0, lastOpenedDate = null))
    }

    /**
     * Port of RemoveBook (+ CleanupUnusedCovers minimally): deletes the row,
     * the EPUB file, and the cover file when no remaining book references it.
     * (WPF keeps the file on disk; mobile deletes it to free app storage —
     * the confirm dialog text reflects this.)
     */
    suspend fun removeBook(id: String) {
        val book = dao.getById(id) ?: return
        dao.delete(book)
        runCatching { File(book.filePath).takeIf { it.isFile }?.delete() }
        // Drop this book's extracted chapter cache (ReaderScreen re-extracts on
        // demand; the startup prune in NovelfaApplication is the backstop).
        cacheDir?.let { dir ->
            runCatching {
                File(dir, "epub_open" + File.separatorChar + id)
                    .takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
        val cover = book.coverPath?.takeIf { it.isNotBlank() } ?: return
        val stillUsed = dao.allBooks().first().any { it.coverPath == cover }
        if (!stillUsed) {
            runCatching { File(cover).takeIf { it.isFile }?.delete() }
        }
    }

    /** Port of TogglePrivate. */
    suspend fun togglePrivate(id: String) {
        val book = dao.getById(id) ?: return
        dao.update(book.copy(isPrivate = !book.isPrivate))
    }

    /** Remove a single book from its collection (port of RemoveFromCollection). */
    suspend fun removeFromCollection(id: String) {
        val book = dao.getById(id) ?: return
        dao.update(book.copy(collectionName = null))
    }
}
