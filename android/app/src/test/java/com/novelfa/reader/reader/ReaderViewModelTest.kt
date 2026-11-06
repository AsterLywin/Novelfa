package com.novelfa.reader.reader

import android.content.Context
import com.novelfa.reader.data.BookRepository
import com.novelfa.reader.data.SeekableZip
import com.novelfa.reader.domain.Book
import com.novelfa.reader.domain.ContentHashChange
import com.novelfa.reader.domain.contentHashChange
import com.novelfa.reader.domain.isCompleted
import java.io.File
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

private class FakeReaderRepo(initial: List<Book>) : BookRepository {
    private val data = MutableStateFlow(initial)
    val updated = mutableListOf<Book>()

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
        updated += book
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

    override suspend fun noteContentHash(id: String, freshHash: String): ContentHashChange {
        val cur = data.value.firstOrNull { it.id == id } ?: return ContentHashChange.NO_BOOK
        return when (contentHashChange(cur.contentHash, freshHash)) {
            ContentHashChange.FIRST_KNOWN -> {
                update(cur.copy(contentHash = freshHash))
                ContentHashChange.FIRST_KNOWN
            }
            ContentHashChange.UNCHANGED -> ContentHashChange.UNCHANGED
            ContentHashChange.CHANGED -> {
                update(
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

    override suspend fun backfillCover(context: Context, book: Book, resolvedFile: File) {}

    override suspend fun backfillDirectPdf(context: Context, book: Book) {}

    override suspend fun backfillDirectCbz(context: Context, book: Book, seekable: SeekableZip) {}
}

class ReaderViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(id: String = "b1"): Book = Book(
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

    private fun vm(repo: FakeReaderRepo, chapters: List<Chapter>, id: String = "b1") =
        ReaderViewModel(repo, id, repo.let { runBlocking { it.book(id) } } ?: book(id), chapters)

    @Test
    fun loadChapter_setsHtmlAndSavesProgress() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        assertTrue(v.loadChapter(0))
        assertTrue(v.html.contains("سلام دنیا"))
        assertTrue(v.html.contains("dir=\"rtl\""))
        assertTrue(v.html.contains("file:"))
        assertEquals(1, v.docGen)
        assertEquals(0, v.contFirst)
        assertEquals(0, v.contLast)
        assertEquals(0, v.index)
        assertEquals(2, v.chapters.size)
        val saved = repo.book("b1")!!
        assertEquals(0, saved.lastChapterIndex)
        assertNotNull(saved.lastOpenedDate)
    }

    @Test
    fun loadChapter_outOfBounds_false() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        assertFalse(v.loadChapter(-1))
        assertFalse(v.loadChapter(2))
        assertEquals("", v.html)
    }

    @Test
    fun appendNext_incrementsContLast() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.loadChapter(0)
        assertTrue(v.appendNext())
        assertEquals(1, v.contLast)
        assertEquals(0, v.contFirst)
        assertTrue(v.html.contains("Hello world"))
        assertTrue(v.html.contains("فصل 2"))
    }

    @Test
    fun appendNext_atEnd_false() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.loadChapter(1)
        assertFalse(v.appendNext())
        assertEquals(1, v.contLast)
    }

    @Test
    fun prependPrev_decrementsContFirst() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.loadChapter(1)
        assertTrue(v.prependPrev())
        assertEquals(0, v.contFirst)
        assertEquals(1, v.contLast)
        assertTrue(v.html.contains("سلام دنیا"))
    }

    @Test
    fun prependPrev_atStart_false() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.loadChapter(0)
        assertFalse(v.prependPrev())
        assertEquals(0, v.contFirst)
    }

    @Test
    fun staleDocGen_ignored() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.loadChapter(0)
        assertFalse(v.appendNext(expectedGen = v.docGen + 99))
        assertEquals(0, v.contLast)
        v.loadChapter(1)
        assertFalse(v.prependPrev(expectedGen = 1))
        assertEquals(1, v.contFirst)
    }

    @Test
    fun onScroll_nearBottom_appends() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.readingMode = ReadingMode.CONTINUOUS_VERTICAL
        v.loadChapter(0)
        v.onScroll(top = 1500, height = 2000, client = 500)
        assertEquals(1, v.contLast)
    }

    @Test
    fun onScroll_topZero_prepends() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.readingMode = ReadingMode.CONTINUOUS_VERTICAL
        v.loadChapter(1)
        v.onScroll(top = 0, height = 2000, client = 500)
        assertEquals(0, v.contFirst)
    }

    @Test
    fun onScroll_singleMode_noop() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.readingMode = ReadingMode.PAGED_HORIZONTAL
        v.loadChapter(0)
        v.onScroll(top = 1500, height = 2000, client = 500)
        assertEquals(0, v.contLast)
    }

    @Test
    fun applySpacing_clampedAndPersisted() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        v.applySpacing(9.9, 999, 9.9)
        assertEquals(3.0, v.lineHeight, 0.001)
        assertEquals(160, v.sideMargin)
        assertEquals(2.5, v.paragraphSpacing, 0.001)
        v.applySpacing(0.1, -5, -1.0)
        assertEquals(1.2, v.lineHeight, 0.001)
        assertEquals(0, v.sideMargin)
        assertEquals(0.0, v.paragraphSpacing, 0.001)
        val saved = repo.book("b1")!!
        assertEquals(1.2, saved.lineHeight, 0.001)
        assertEquals(0, saved.sideMargin)
    }

    private fun manyChapters(n: Int): List<Chapter> {
        val dir = tmp.newFolder("many")
        return (0 until n).map { i ->
            val f = dir.resolve("ch$i.xhtml").apply {
                writeText("<html><body><p>Body[$i]</p></body></html>", Charsets.UTF_8)
            }
            Chapter("فصل ${i + 1}", f)
        }
    }

    @Test
    fun continuousWindow_capsSpanAt7_onAppend() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, manyChapters(12))
        v.readingMode = ReadingMode.CONTINUOUS_VERTICAL
        assertTrue(v.loadChapter(0))
        repeat(10) { assertTrue(v.appendNext()) }
        assertEquals(10, v.contLast)
        assertTrue(v.contLast - v.contFirst + 1 <= ReaderViewModel.MAX_WINDOW_SPAN)
        // Overflow drops the far end via dropJs: no full reload, so the
        // kind stays "append" and the dropped range is published.
        assertEquals("append", v.lastChunkKind)
        assertNotNull(v.lastDropped)
        assertTrue(v.html.contains("Body[10]"))
        assertFalse(v.html.contains("Body[0]"))
    }

    @Test
    fun continuousWindow_capsSpanAt7_onPrepend() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, manyChapters(12))
        v.readingMode = ReadingMode.CONTINUOUS_VERTICAL
        assertTrue(v.loadChapter(11))
        repeat(10) { assertTrue(v.prependPrev()) }
        assertEquals(1, v.contFirst)
        assertTrue(v.contLast - v.contFirst + 1 <= ReaderViewModel.MAX_WINDOW_SPAN)
        assertEquals("prepend", v.lastChunkKind)
        assertNotNull(v.lastDropped)
        assertTrue(v.html.contains("Body[1]"))
        assertFalse(v.html.contains("Body[11]"))
    }

    @Test
    fun changeFontFamily_persistsAndRebuilds() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        assertTrue(v.loadChapter(0))
        val fake = tmp.newFile("Vazir.ttf")
        assertTrue(v.changeFontFamily("Vazir", listOf(fake)))
        assertEquals("Vazir", v.fontFamily)
        assertTrue(v.html.contains("Vazir"))
        assertEquals("Vazir", repo.book("b1")?.fontFamily)
        assertFalse(v.changeFontFamily("  ", emptyList()))
    }

    @Test
    fun pagedAnchor_savedWithChapterAndClearedOnChapterChange() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        assertTrue(v.loadChapter(0))
        v.notePagedAnchor("3:12")
        assertEquals("3:12", v.pagedAnchor)
        assertEquals(0, v.pagedAnchorChapter)
        // Throttled save may skip the immediate write (loadChapter
        // just saved); a forced save must persist the anchor.
        v.saveProgress(0, force = true)
        assertEquals("3:12", repo.book("b1")!!.lastAnchor)
        assertEquals(0, repo.book("b1")!!.lastChapterIndex)
        // Moving to another chapter must not carry the old chapter's
        // anchor with it.
        assertTrue(v.loadChapter(1))
        assertEquals(null, repo.book("b1")!!.lastAnchor)
        assertEquals(1, repo.book("b1")!!.lastChapterIndex)
        v.notePagedAnchor("bad-anchor")
        assertEquals("3:12", v.pagedAnchor)
        v.clearPagedAnchor()
        assertEquals(null, v.pagedAnchor)
        assertEquals(-1, v.pagedAnchorChapter)
    }

    @Test
    fun applyTheme_rebuildsWithNewColors() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, twoChapters())
        assertTrue(v.loadChapter(0))
        assertTrue(v.html.contains("#1C1A2A"))
        assertTrue(v.applyTheme(com.novelfa.reader.theme.Themes.WHITE))
        assertEquals(com.novelfa.reader.theme.Themes.WHITE, v.theme)
        assertTrue(v.html.contains("#FFFFFF"))
        assertFalse(v.html.contains("#1C1A2A"))
    }

    private fun footnoteChapters(): List<Chapter> {
        val dir = tmp.newFolder("fnchaps")
        val ch1 = dir.resolve("ch1.xhtml").apply {
            writeText(
                "<html><body><p>متن <sup><a href=\"ch2.xhtml#fn1\">1</a></sup> " +
                    "و <sup><a href=\"#novelfa-fn-1\">[1]</a></sup></p></body></html>",
                Charsets.UTF_8
            )
        }
        val ch2 = dir.resolve("ch2.xhtml").apply {
            writeText(
                "<html><body><p id=\"fn1\">متن پاورقی</p>" +
                    "<ol><li id=\"novelfa-fn-1\">" +
                    "<a href=\"#novelfa-fnref-1\">↩</a> متن پاورقی داکس</li></ol>" +
                    "</body></html>",
                Charsets.UTF_8
            )
        }
        return listOf(Chapter("فصل 1", ch1), Chapter("فصل 2", ch2))
    }

    @Test
    fun footnoteFor_crossChapterEndnote_returnsTextAndChapter() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, footnoteChapters())
        val note = v.footnoteFor("ch2.xhtml#fn1", 0, "1")
        assertNotNull(note)
        assertEquals("متن پاورقی", note!!.text)
        assertEquals("1", note.number)
        assertEquals(1, note.chapterIdx)
        assertEquals("fn1", note.fragment)
    }

    @Test
    fun footnoteFor_docxStyleNoteInLastChapter_foundByScan() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, footnoteChapters())
        // resolveLink lands on the reference's own chapter (0), where
        // the id does not exist; the scan must find it in chapter 1.
        val note = v.footnoteFor("#novelfa-fn-1", 0, "[1]")
        assertNotNull(note)
        assertEquals("متن پاورقی داکس", note!!.text)
        assertEquals("1", note.number)
        assertEquals(1, note.chapterIdx)
    }

    @Test
    fun footnoteFor_missingNote_returnsNull() = runBlocking {
        val repo = FakeReaderRepo(listOf(book()))
        val v = vm(repo, footnoteChapters())
        assertEquals(null, v.footnoteFor("#no-such-note", 0, "9"))
    }
}
