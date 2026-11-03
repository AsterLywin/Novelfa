package com.novelfa.reader.data

import android.content.Context
import com.novelfa.reader.domain.Book
import com.novelfa.reader.domain.ContentHashChange
import com.novelfa.reader.domain.contentHashChange
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
            // A hash discovered while resolving (working-copy
            // build or sidecar hit) is recorded through the
            // single F2 rule: first knowledge persists, a real
            // change persists AND resets progress, unchanged is
            // a no-op. Never the old null-only persist, which
            // left a stale hash on the row after an edit.
            noteContentHash(book.id, hash)
        }

    override suspend fun noteContentHash(
        id: String,
        freshHash: String
    ): ContentHashChange {
        val cur = dao.getById(id) ?: return ContentHashChange.NO_BOOK
        return when (contentHashChange(cur.contentHash, freshHash)) {
            ContentHashChange.FIRST_KNOWN -> {
                dao.update(cur.copy(contentHash = freshHash))
                ContentHashChange.FIRST_KNOWN
            }
            ContentHashChange.UNCHANGED -> ContentHashChange.UNCHANGED
            ContentHashChange.CHANGED -> {
                // New content identity: the old progress belongs
                // to the old content and must not survive onto
                // the new one (F2: new content is never mistaken
                // as completed). The ledger is untouched — keyed
                // by content hash, the old entry stays under the
                // old key and the new content starts from zero.
                dao.update(
                    cur.copy(
                        contentHash = freshHash,
                        lastChapterIndex = 0,
                        lastOpenedDate = null,
                        lastAnchor = null
                    )
                )
                ContentHashChange.CHANGED
            }
            ContentHashChange.NO_BOOK -> ContentHashChange.NO_BOOK
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
