package com.novelfa.reader.reader

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.model.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeReaderDao(initial: List<BookEntity>) : BookDao {
    private val data = MutableStateFlow(initial)
    val updated = mutableListOf<BookEntity>()

    override suspend fun insert(book: BookEntity) {
        data.value = data.value + book
    }

    override suspend fun update(book: BookEntity) {
        updated += book
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
}

class ReaderViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(id: String = "b1"): BookEntity = BookEntity(
        id = id,
        title = "Test Book",
        filePath = "/tmp/test.epub",
        totalChapters = 2,
        dateAdded = "2026-01-01T00:00:00Z"
    )

    private fun twoChapters(): List<Chapter> {
        val dir = tmp.newFolder("chaps")
        val ch1 = dir.resolve("ch1.xhtml").apply {
            writeText("<html><body><p>سلام دنیا</p><img src=\"i.png\"></body></html>", Charsets.UTF_8)
        }
        val ch2 = dir.resolve("ch2.xhtml").apply {
            writeText("<html><body><p>Hello world</p></body></html>", Charsets.UTF_8)
        }
        return listOf(Chapter("فصل 1", ch1), Chapter("فصل 2", ch2))
    }

    private fun vm(dao: FakeReaderDao, chapters: List<Chapter>, id: String = "b1") =
        ReaderViewModel(dao, id, dao.let { runBlocking { it.getById(id) } } ?: book(id), chapters)

    @Test
    fun loadChapter_setsHtmlAndSavesProgress() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        assertTrue(v.loadChapter(0))
        assertTrue(v.html.contains("سلام دنیا"))
        assertTrue(v.html.contains("dir=\"rtl\""))
        assertTrue(v.html.contains("file:"))
        assertEquals(1, v.docGen)
        assertEquals(0, v.contFirst)
        assertEquals(0, v.contLast)
        assertEquals(0, v.index)
        assertEquals("1 از 2", v.positionText)
        val saved = dao.getById("b1")!!
        assertEquals(0, saved.lastChapterIndex)
        assertNotNull(saved.lastOpenedDate)
    }

    @Test
    fun loadChapter_outOfBounds_false() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        assertFalse(v.loadChapter(-1))
        assertFalse(v.loadChapter(2))
        assertEquals("", v.html)
    }

    @Test
    fun appendNext_incrementsContLast() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.loadChapter(0)
        assertTrue(v.appendNext())
        assertEquals(1, v.contLast)
        assertEquals(0, v.contFirst)
        assertTrue(v.html.contains("Hello world"))
        assertTrue(v.html.contains("فصل 2"))
    }

    @Test
    fun appendNext_atEnd_false() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.loadChapter(1)
        assertFalse(v.appendNext())
        assertEquals(1, v.contLast)
    }

    @Test
    fun prependPrev_decrementsContFirst() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.loadChapter(1)
        assertTrue(v.prependPrev())
        assertEquals(0, v.contFirst)
        assertEquals(1, v.contLast)
        assertTrue(v.html.contains("سلام دنیا"))
    }

    @Test
    fun prependPrev_atStart_false() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.loadChapter(0)
        assertFalse(v.prependPrev())
        assertEquals(0, v.contFirst)
    }

    @Test
    fun staleDocGen_ignored() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.loadChapter(0)
        assertFalse(v.appendNext(expectedGen = v.docGen + 99))
        assertEquals(0, v.contLast)
        v.loadChapter(1)
        assertFalse(v.prependPrev(expectedGen = 1))
        assertEquals(1, v.contFirst)
    }

    @Test
    fun onScroll_nearBottom_appends() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.continuous = true
        v.loadChapter(0)
        v.onScroll(top = 1500, height = 2000, client = 500)
        assertEquals(1, v.contLast)
    }

    @Test
    fun onScroll_topZero_prepends() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.continuous = true
        v.loadChapter(1)
        v.onScroll(top = 0, height = 2000, client = 500)
        assertEquals(0, v.contFirst)
    }

    @Test
    fun onScroll_singleMode_noop() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.continuous = false
        v.loadChapter(0)
        v.onScroll(top = 1500, height = 2000, client = 500)
        assertEquals(0, v.contLast)
    }

    @Test
    fun applySpacing_clampedAndPersisted() = runBlocking {
        val dao = FakeReaderDao(listOf(book()))
        val v = vm(dao, twoChapters())
        v.applySpacing(9.9, 999, 9.9)
        assertEquals(3.0, v.lineHeight, 0.001)
        assertEquals(160, v.sideMargin)
        assertEquals(2.5, v.paragraphSpacing, 0.001)
        v.applySpacing(0.1, -5, -1.0)
        assertEquals(1.2, v.lineHeight, 0.001)
        assertEquals(0, v.sideMargin)
        assertEquals(0.0, v.paragraphSpacing, 0.001)
        val saved = dao.getById("b1")!!
        assertEquals(1.2, saved.lineHeight, 0.001)
        assertEquals(0, saved.sideMargin)
    }
}
