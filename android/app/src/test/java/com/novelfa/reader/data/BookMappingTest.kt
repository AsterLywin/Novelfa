package com.novelfa.reader.data

import com.novelfa.reader.domain.Book
import com.novelfa.reader.domain.isCompleted as bookIsCompleted
import com.novelfa.reader.domain.isInProgress as bookIsInProgress
import com.novelfa.reader.domain.percentDisplay as bookPercentDisplay
import com.novelfa.reader.domain.progressPercent as bookProgressPercent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The repository mapping must be the identity in both directions:
 * every field the UI can change has to survive a Book -> entity ->
 * Book round trip, or a save through the repository would silently
 * drop it. The progress helpers must also agree between the domain
 * model and the entity, since call sites moved from one to the other.
 */
class BookMappingTest {

    private fun fullEntity() = BookEntity(
        id = "b1",
        title = "Test Book",
        filePath = "/books/b1.epub",
        coverPath = "/covers/b1.png",
        totalChapters = 12,
        lastChapterIndex = 5,
        fontFamily = "Vazirmatn",
        fontSize = 20,
        lineHeight = 1.8,
        sideMargin = 24,
        paragraphSpacing = 0.5,
        dateAdded = "2026-01-01T00:00:00Z",
        lastOpenedDate = "2026-01-05T00:00:00Z",
        isPrivate = true,
        isFavorite = true,
        collectionName = "favorites",
        originalFileName = "original.epub",
        lastAnchor = "li:42",
        sourceUri = "content://com.example/books/7",
        importMode = "IN_PLACE",
        contentHash = "deadbeef"
    )

    @Test
    fun roundTrip_allFields_survives() {
        val entity = fullEntity()
        assertEquals(entity, entity.toBook().toEntity())
    }

    @Test
    fun roundTrip_defaults_survives() {
        val entity = BookEntity(
            id = "b2",
            title = "Plain",
            filePath = "/books/b2.epub",
            totalChapters = 3,
            dateAdded = "2026-01-01T00:00:00Z"
        )
        assertEquals(entity, entity.toBook().toEntity())
    }

    @Test
    fun toBook_copiesEveryField() {
        val book = fullEntity().toBook()
        val e = fullEntity()
        assertEquals(e.id, book.id)
        assertEquals(e.title, book.title)
        assertEquals(e.filePath, book.filePath)
        assertEquals(e.coverPath, book.coverPath)
        assertEquals(e.totalChapters, book.totalChapters)
        assertEquals(e.lastChapterIndex, book.lastChapterIndex)
        assertEquals(e.fontFamily, book.fontFamily)
        assertEquals(e.fontSize, book.fontSize)
        assertEquals(e.lineHeight, book.lineHeight, 0.0)
        assertEquals(e.sideMargin, book.sideMargin)
        assertEquals(e.paragraphSpacing, book.paragraphSpacing, 0.0)
        assertEquals(e.dateAdded, book.dateAdded)
        assertEquals(e.lastOpenedDate, book.lastOpenedDate)
        assertEquals(e.isPrivate, book.isPrivate)
        assertEquals(e.isFavorite, book.isFavorite)
        assertEquals(e.collectionName, book.collectionName)
        assertEquals(e.originalFileName, book.originalFileName)
        assertEquals(e.lastAnchor, book.lastAnchor)
        assertEquals(e.sourceUri, book.sourceUri)
        assertEquals(e.importMode, book.importMode)
        assertEquals(e.contentHash, book.contentHash)
    }

    @Test
    fun helpers_domainAndEntityAgree() {
        val cases = listOf(
            BookEntity(id = "fresh", title = "t", filePath = "f", totalChapters = 10, dateAdded = "x"),
            BookEntity(id = "reading", title = "t", filePath = "f", totalChapters = 10, lastChapterIndex = 2, dateAdded = "x", lastOpenedDate = "y"),
            BookEntity(id = "done", title = "t", filePath = "f", totalChapters = 10, lastChapterIndex = 9, dateAdded = "x", lastOpenedDate = "y"),
            BookEntity(id = "zero", title = "t", filePath = "f", totalChapters = 0, dateAdded = "x", lastOpenedDate = "y")
        )
        for (entity in cases) {
            val book: Book = entity.toBook()
            assertEquals(progressPercent(entity), bookProgressPercent(book), 0.0)
            assertEquals(isCompleted(entity), bookIsCompleted(book))
            assertEquals(isInProgress(entity), bookIsInProgress(book))
            assertEquals(percentDisplay(entity), bookPercentDisplay(book))
        }
    }
}
