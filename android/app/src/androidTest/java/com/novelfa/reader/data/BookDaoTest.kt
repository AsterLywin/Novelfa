package com.novelfa.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDaoTest {
    private lateinit var db: ReaderDb
    private lateinit var dao: BookDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ReaderDb::class.java).build()
        dao = db.bookDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insert_getById_search_delete() = runBlocking {
        val e = BookEntity(
            id = "t1",
            title = "My Book",
            filePath = "/books/a.epub",
            totalChapters = 5,
            dateAdded = "2026-01-01T00:00:00Z"
        )
        dao.insert(e)
        assertEquals("My Book", dao.getById("t1")?.title)
        assertEquals(1, dao.search("My").first().size)
        assertEquals(0, dao.search("zzz-no-match").first().size)
        dao.delete(e)
        assertNull(dao.getById("t1"))
    }

    @Test
    fun update_changesFields() = runBlocking {
        val e = BookEntity(
            id = "u1",
            title = "Before",
            filePath = "/books/u.epub",
            totalChapters = 4,
            dateAdded = "2026-01-01T00:00:00Z"
        )
        dao.insert(e)
        dao.update(e.copy(title = "After", isFavorite = true, lastChapterIndex = 3))
        val got = dao.getById("u1")
        assertEquals("After", got?.title)
        assertEquals(true, got?.isFavorite)
        assertEquals(3, got?.lastChapterIndex)
    }

    @Test
    fun allBooks_emitsInsertsAndDeletes() = runBlocking {
        assertEquals(0, dao.allBooks().first().size)
        val a = BookEntity(id = "a1", title = "A", filePath = "/books/a.epub", totalChapters = 1, dateAdded = "2026-01-01T00:00:00Z")
        val b = BookEntity(id = "a2", title = "B", filePath = "/books/b.epub", totalChapters = 1, dateAdded = "2026-01-01T00:00:00Z")
        dao.insert(a)
        dao.insert(b)
        assertEquals(2, dao.allBooks().first().size)
        dao.delete(a)
        assertEquals(listOf("a2"), dao.allBooks().first().map { it.id })
    }

    @Test
    fun byCollection_returnsOnlyMembers() = runBlocking {
        val inCol = BookEntity(id = "c1", title = "In", filePath = "/books/c1.epub", totalChapters = 2, dateAdded = "2026-01-01T00:00:00Z", collectionName = "SciFi")
        val other = BookEntity(id = "c2", title = "Out", filePath = "/books/c2.epub", totalChapters = 2, dateAdded = "2026-01-01T00:00:00Z", collectionName = "Other")
        val none = BookEntity(id = "c3", title = "None", filePath = "/books/c3.epub", totalChapters = 2, dateAdded = "2026-01-01T00:00:00Z")
        dao.insert(inCol)
        dao.insert(other)
        dao.insert(none)
        assertEquals(listOf("c1"), dao.byCollection("SciFi").first().map { it.id })
        assertEquals(0, dao.byCollection("Missing").first().size)
    }
}
