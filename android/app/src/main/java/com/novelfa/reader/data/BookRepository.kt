package com.novelfa.reader.data

import android.content.Context
import com.novelfa.reader.domain.Book
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * The UI-facing facade over the book store. Screens, ViewModels and
 * helpers depend on this interface, never on [BookDao] or [BookEntity]:
 * the Room types stay in the data layer, behind this boundary.
 *
 * The interface lives in data (not pure domain) on purpose: two of its
 * operations need an Android [Context] ([InPlaceSource] resolves
 * `content://` books through the ContentResolver, [CoverBackfill]
 * extracts covers through it), and domain must stay pure JVM.
 * Everything else here is plain CRUD + the finished-book count the
 * settings/rank code needs.
 */
interface BookRepository {
    /** All books, as a Flow (Room updates propagate automatically). */
    fun books(): Flow<List<Book>>

    /** Books in one collection, as a Flow. */
    fun booksInCollection(name: String): Flow<List<Book>>

    /** Title search, as a Flow. */
    fun searchBooks(query: String): Flow<List<Book>>

    /** One book by id, or null. */
    suspend fun book(id: String): Book?

    /** Insert (REPLACE on id conflict, like the DAO). */
    suspend fun save(book: Book)

    /** Update an existing row. */
    suspend fun update(book: Book)

    /** Delete a row. */
    suspend fun delete(book: Book)

    /** Finished, non-private books (WPF CompletedCount predicate). */
    suspend fun finishedCount(): Int

    /**
     * True when an in-place book's source is still reachable (copy
     * books are always reachable through their stored file — callers
     * check [Book.importMode] first, as before).
     */
    fun isSourceAvailable(context: Context, book: Book): Boolean

    /**
     * True when the book's file lives outside the app's own storage
     * (an in-place book picked by path, or by `file://` Uri): such a
     * file must never be deleted with the row, only unlinked.
     */
    fun isExternalFile(book: Book): Boolean

    /**
     * Resolve a book to a [File] the engines can open: the stored file
     * for copy books, the user's real file (or a transient working
     * copy) for in-place books. A content hash discovered while
     * resolving an in-place `content://` book is persisted to the row
     * when the row has none yet — exactly what the reader used to do
     * inline in its own resolve callback. Throws [java.io.IOException]
     * when an in-place source is missing, as [InPlaceSource] does.
     */
    suspend fun resolveBookFile(context: Context, book: Book): File

    /**
     * First-open cover backfill for `content://` in-place books (see
     * [CoverBackfill]): a register-only import never extracted the
     * real cover, so the row still shows a generated cover or none.
     * Idempotent; a no-op for books that already have a real cover.
     */
    suspend fun backfillCover(context: Context, book: Book, resolvedFile: File)

    /**
     * Direct-route counterpart of [backfillCover] for PDFs: the real
     * first page replaces the generated cover, read from the
     * provider's descriptor directly instead of a working copy.
     */
    suspend fun backfillDirectPdf(context: Context, book: Book)

    /**
     * Direct-route counterpart of [backfillCover] for comics: the
     * first image entry is the cover, read from the archive directly.
     */
    suspend fun backfillDirectCbz(context: Context, book: Book, seekable: SeekableZip)
}
