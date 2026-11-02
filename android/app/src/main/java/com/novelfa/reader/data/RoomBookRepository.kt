package com.novelfa.reader.data

import android.content.Context
import com.novelfa.reader.domain.Book
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [BookRepository] over the Room DAO (+ in-place/cover helpers). */
class RoomBookRepository(
    private val dao: BookDao
) : BookRepository {

    override fun books(): Flow<List<Book>> =
        dao.allBooks().map { list -> list.map { it.toBook() } }

    override fun booksInCollection(name: String): Flow<List<Book>> =
        dao.byCollection(name).map { list -> list.map { it.toBook() } }

    override fun searchBooks(query: String): Flow<List<Book>> =
        dao.search(query).map { list -> list.map { it.toBook() } }

    override suspend fun book(id: String): Book? =
        dao.getById(id)?.toBook()

    override suspend fun save(book: Book) {
        dao.insert(book.toEntity())
    }

    override suspend fun update(book: Book) {
        dao.update(book.toEntity())
    }

    override suspend fun delete(book: Book) {
        dao.delete(book.toEntity())
    }

    override suspend fun finishedCount(): Int =
        dao.allBooks().first().count { !it.isPrivate && isCompleted(it) }

    override fun isSourceAvailable(context: Context, book: Book): Boolean =
        InPlaceSource.isSourceAvailable(context, book.toEntity())

    override fun isExternalFile(book: Book): Boolean =
        InPlaceSource.isExternalFile(book.toEntity())

    override suspend fun resolveBookFile(context: Context, book: Book): File =
        InPlaceSource.resolve(context, book.toEntity()) { hash ->
            // Persist the deferred content hash (import-speed step 2):
            // this open digested it for free. Only when the row has
            // none yet — a stored hash is never overwritten.
            val cur = dao.getById(book.id)
            if (cur != null && cur.contentHash == null) {
                dao.update(cur.copy(contentHash = hash))
            }
        }

    override suspend fun backfillCover(context: Context, book: Book, resolvedFile: File) {
        CoverBackfill.backfill(context, dao, book.toEntity(), resolvedFile)
    }

    override suspend fun backfillDirectPdf(context: Context, book: Book) {
        CoverBackfill.backfillDirectPdf(context, dao, book.toEntity())
    }

    override suspend fun backfillDirectCbz(context: Context, book: Book, seekable: SeekableZip) {
        CoverBackfill.backfillDirectCbz(context, dao, book.toEntity(), seekable)
    }
}
