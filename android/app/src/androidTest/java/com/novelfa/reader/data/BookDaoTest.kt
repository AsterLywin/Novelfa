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
}
