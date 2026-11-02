package com.novelfa.reader.settings

import android.content.Context
import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.data.SeekableZip
import com.novelfa.reader.domain.Book
import com.novelfa.reader.domain.isCompleted
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Shared in-memory fake BookRepository for settings tests. */
internal class FakeBookRepository(initial: List<Book>) : BookRepository {
    private val data = MutableStateFlow(initial)

    override fun books(): Flow<List<Book>> = data

    override fun booksInCollection(name: String): Flow<List<Book>> =
        data.map { list -> list.filter { it.collectionName == name } }

    override fun searchBooks(query: String): Flow<List<Book>> =
        data.map { list -> list.filter { it.title.contains(query, ignoreCase = true) } }

    override suspend fun book(id: String): Book? =
        data.value.firstOrNull { it.id == id }

    override suspend fun save(book: Book) {
        data.value = data.value + book
    }

    override suspend fun update(book: Book) {
        data.value = data.value.map { if (it.id == book.id) book else it }
    }

    override suspend fun delete(book: Book) {
        data.value = data.value - book
    }

    override suspend fun finishedCount(): Int =
        data.value.count { !it.isPrivate && isCompleted(it) }

    override fun isSourceAvailable(context: Context, book: Book): Boolean = true

    override fun isExternalFile(book: Book): Boolean = false

    override suspend fun resolveBookFile(context: Context, book: Book): File =
        File(book.filePath)

    override suspend fun backfillCover(context: Context, book: Book, resolvedFile: File) {}

    override suspend fun backfillDirectPdf(context: Context, book: Book) {}

    override suspend fun backfillDirectCbz(context: Context, book: Book, seekable: SeekableZip) {}

    fun current(): List<Book> = data.value
}

internal fun testBook(
    id: String,
    title: String = "Title $id",
    filePath: String = "/books/$id.epub",
    coverPath: String? = null,
    totalChapters: Int = 10,
    lastChapterIndex: Int = 0,
    lastOpenedDate: String? = null,
    isPrivate: Boolean = false,
    isFavorite: Boolean = false,
    collectionName: String? = null
) = Book(
    id = id,
    title = title,
    filePath = filePath,
    coverPath = coverPath,
    totalChapters = totalChapters,
    lastChapterIndex = lastChapterIndex,
    dateAdded = "2026-01-01T00:00:00Z",
    lastOpenedDate = lastOpenedDate,
    isPrivate = isPrivate,
    isFavorite = isFavorite,
    collectionName = collectionName
)

/** In-memory PinPrefs fake. */
internal class FakePinPrefs : PinPrefs {
    var hash: String? = null
    override suspend fun pinHash(): String? = hash
    override suspend fun setPinHash(hash: String?) {
        this.hash = hash
    }
}

/** In-memory SupportPrefs fake. */
internal class FakeSupportPrefs(
    private var seconds: Int = 0,
    private var enabled: Boolean = true
) : SupportPrefs {
    override suspend fun getSeconds(): Int = seconds
    override suspend fun setSeconds(value: Int) {
        seconds = value
    }
    override suspend fun isEnabled(): Boolean = enabled
    override suspend fun setEnabled(value: Boolean) {
        enabled = value
    }
    /** Test-only direct access (replaces former property reads). */
    fun currentSeconds(): Int = seconds
}
