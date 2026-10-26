package com.novelfa.reader.library

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeBookDao(initial: List<BookEntity>) : BookDao {
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

}

private fun book(
    id: String,
    title: String,
    filePath: String = "/books/$id.epub",
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
    totalChapters = totalChapters,
    lastChapterIndex = lastChapterIndex,
    dateAdded = "2026-01-01T00:00:00Z",
    lastOpenedDate = lastOpenedDate,
    isPrivate = isPrivate,
    isFavorite = isFavorite,
    collectionName = collectionName
)

class LibraryViewModelTest {

    private val completed = book("c1", "Done Book", lastChapterIndex = 9, lastOpenedDate = "2026-01-05T00:00:00Z")
    private val reading = book("c2", "Reading Book", lastChapterIndex = 2, lastOpenedDate = "2026-01-02T00:00:00Z")
    private val fresh = book("c3", "New Book")
    private val secret = book("c4", "Secret Book", lastChapterIndex = 9, lastOpenedDate = "2026-01-06T00:00:00Z", isPrivate = true)

    private fun vm(books: List<BookEntity> = listOf(completed, reading, fresh, secret)) =
        // Importer needs Android Context so it is intentionally not faked here;
        // pass a null-guard via reflection-free placeholder is impossible on JVM,
        // so tests construct with a test-only overload documented in LibraryViewModel.
        LibraryViewModel(FakeBookDao(books), pinGate = PinGate { false })

    @Test
    fun allTab_excludesPrivate() = runBlocking {
        assertEquals(listOf("c1", "c2", "c3"), vm().filtered(Tab.ALL, "").first().map { it.id }.sorted())
    }

    @Test
    fun readingTab_returnsOnlyInProgress() = runBlocking {
        assertEquals(listOf("c2"), vm().filtered(Tab.READING, "").first().map { it.id })
    }

    @Test
    fun completedTab_returnsOnlyCompleted() = runBlocking {
        assertEquals(listOf("c1"), vm().filtered(Tab.COMPLETED, "").first().map { it.id })
    }

    @Test
    fun recentTab_returnsOpenedSortedDesc() = runBlocking {
        assertEquals(listOf("c1", "c2"), vm().filtered(Tab.RECENT, "").first().map { it.id })
    }

    @Test
    fun privateTab_returnsOnlyPrivate() = runBlocking {
        assertEquals(listOf("c4"), vm().filtered(Tab.PRIVATE, "").first().map { it.id })
    }

    @Test
    fun search_isCaseInsensitiveOnTitle() = runBlocking {
        assertEquals(listOf("c2"), vm().filtered(Tab.ALL, "reading").first().map { it.id })
        assertEquals(listOf("c1"), vm().filtered(Tab.ALL, "DONE").first().map { it.id })
    }

    @Test
    fun gridOrder_sortsFaWithNaturalNumbers() = runBlocking {
        val v = vm(
            listOf(
                book("a", "جلد 10", filePath = "/books/a.epub"),
                book("b", "جلد 2", filePath = "/books/b.epub")
            )
        )
        assertEquals(listOf("b", "a"), v.filtered(Tab.ALL, "").first().map { it.id })
    }

    @Test
    fun displayTitle_dedupShowsFileName() {
        val dup1 = book("d1", "Same Title", filePath = "/books/first.epub")
        val dup2 = book("d2", "Same Title", filePath = "/books/second.epub")
        val all = listOf(dup1, dup2)
        assertEquals("first", displayTitle(dup1, all))
        assertEquals("second", displayTitle(dup2, all))
    }

    @Test
    fun displayTitle_uniqueKeepsTitle() {
        val only = book("u1", "Unique Title", filePath = "/books/unique.epub")
        assertEquals("Unique Title", displayTitle(only, listOf(only)))
    }

    @Test
    fun displayTitle_uuidTitleFallsBackToOriginalFileName() {
        // Converted book with no internal title: the stored title is
        // the storage UUID, but the original file name is known — the
        // library must show the file name, never the UUID.
        val uuid = book(
            "u2",
            "123e4567-e89b-12d3-a456-426614174000",
            filePath = "/books/123e4567-e89b-12d3-a456-426614174000.txt"
        ).copy(originalFileName = "My Book.txt")
        assertEquals("My Book", displayTitle(uuid, listOf(uuid)))
    }

    @Test
    fun favoritesTab_returnsOnlyFavorites() = runBlocking {
        val v = vm(
            listOf(
                book("f1", "Fav One", isFavorite = true),
                book("f2", "Plain One"),
                // Private favorites stay out of the public favorites tab.
                book("f3", "Secret Fav", isFavorite = true, isPrivate = true)
            )
        )
        assertEquals(listOf("f1"), v.filtered(Tab.FAVORITES, "").first().map { it.id })
    }

    @Test
    fun sortBooks_modes() {
        val all = listOf(
            book("s1", "ب", lastChapterIndex = 1, lastOpenedDate = "2026-01-02T00:00:00Z")
                .copy(dateAdded = "2026-01-01T00:00:00Z"),
            book("s2", "الف", lastChapterIndex = 9, lastOpenedDate = "2026-01-03T00:00:00Z")
                .copy(dateAdded = "2026-01-05T00:00:00Z"),
            book("s3", "ج", lastChapterIndex = 0, lastOpenedDate = null)
                .copy(dateAdded = "2026-01-03T00:00:00Z")
        )
        // DEFAULT preserves incoming order.
        assertEquals(listOf("s1", "s2", "s3"), sortBooks(all, all, SortMode.DEFAULT).map { it.id })
        // NAME_AZ uses the Persian natural comparison.
        assertEquals(listOf("s2", "s1", "s3"), sortBooks(all, all, SortMode.NAME_AZ).map { it.id })
        // RECENT_ADDED is dateAdded-desc.
        assertEquals(listOf("s2", "s3", "s1"), sortBooks(all, all, SortMode.RECENT_ADDED).map { it.id })
        // LAST_READ is lastOpenedDate-desc (never-opened last).
        assertEquals(listOf("s2", "s1", "s3"), sortBooks(all, all, SortMode.LAST_READ).map { it.id })
        // MOST_PROGRESS: s2 done (100), s1 in progress, s3 untouched.
        assertEquals(listOf("s2", "s1", "s3"), sortBooks(all, all, SortMode.MOST_PROGRESS).map { it.id })
    }
}
