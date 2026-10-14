package com.novelfa.reader.settings

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Shared fake BookDao for settings tests (mirrors LibraryViewModelTest's fake). */
internal class FakeBookDao(initial: List<BookEntity>) : BookDao {
    private val data = MutableStateFlow(initial)

    override suspend fun insert(book: BookEntity) {
        data.value = data.value + book
    }

    override suspend fun update(book: BookEntity) {
        data.value = data.value.map { if (it.id == book.id) book else it }
    }

    override suspend fun delete(book: BookEntity) {
        data.value = data.value - book
    }

    override suspend fun getById(id: String): BookEntity? =
        data.value.firstOrNull { it.id == id }

    override fun allBooks(): Flow<List<BookEntity>> = data

    override fun byCollection(name: String): Flow<List<BookEntity>> =
        data.map { list -> list.filter { it.collectionName == name } }

    override fun search(query: String): Flow<List<BookEntity>> =
        data.map { list -> list.filter { it.title.contains(query, ignoreCase = true) } }

    fun current(): List<BookEntity> = data.value
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
) = BookEntity(
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
