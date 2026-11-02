package com.novelfa.reader.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 8 RED: CollectionManager (port of LibraryService.cs RenameCollection,
 * RemoveFromCollection/SetCollection, ResetProgress, RemoveBook +
 * CleanupUnusedCovers minimally).
 */
class CollectionOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun epub(name: String): String {
        val f = tmp.newFile(name)
        f.writeText("fake-epub")
        return f.absolutePath
    }

    @Test
    fun renameCollection_updatesAllWithThatName() = runBlocking {
        val repo = FakeBookRepository(
            listOf(
                testBook("a", collectionName = "old"),
                testBook("b", collectionName = "old"),
                testBook("c", collectionName = "other")
            )
        )
        CollectionManager(repo).renameCollection("old", "new")
        assertEquals(listOf("new", "new", "other"), repo.current().map { it.collectionName })
    }

    @Test
    fun renameCollection_blankNew_throws() = runBlocking {
        val repo = FakeBookRepository(listOf(testBook("a", collectionName = "old")))
        try {
            CollectionManager(repo).renameCollection("old", "  ")
            fail("expected CollectionException")
        } catch (e: CollectionException) {
            assertTrue(e.resId != 0)
        }
        assertEquals("old", repo.current().single().collectionName)
    }

    @Test
    fun deleteCollection_nullsOut() = runBlocking {
        val repo = FakeBookRepository(
            listOf(
                testBook("a", collectionName = "gone"),
                testBook("b", collectionName = "kept")
            )
        )
        CollectionManager(repo).deleteCollection("gone")
        assertNull(repo.current().first { it.id == "a" }.collectionName)
        assertEquals("kept", repo.current().first { it.id == "b" }.collectionName)
    }

    @Test
    fun allCollectionNames_distinct() = runBlocking {
        val repo = FakeBookRepository(
            listOf(
                testBook("a", collectionName = "x"),
                testBook("b", collectionName = "x"),
                testBook("c", collectionName = "y"),
                testBook("d")
            )
        )
        assertEquals(listOf("x", "y"), CollectionManager(repo).allCollectionNames().sorted())
    }

    @Test
    fun resetProgress_zeroesIndexAndClearsDate() = runBlocking {
        val repo = FakeBookRepository(
            listOf(testBook("a", lastChapterIndex = 7, lastOpenedDate = "2026-01-05T00:00:00Z"))
        )
        CollectionManager(repo).resetProgress("a")
        val got = repo.current().single()
        // Port of ResetProgress: LastChapterIndex=0 (LastChapterDisplay back to 1),
        // LastOpenedDate cleared so progressPercent returns 0 ("not started").
        assertEquals(0, got.lastChapterIndex)
        assertEquals(1, got.lastChapterIndex + 1)
        assertTrue(got.lastOpenedDate.isNullOrEmpty())
        assertEquals(0.0, com.novelfa.reader.domain.progressPercent(got), 0.001)
    }

    @Test
    fun removeBook_deletesRowEpubAndUnreferencedCover() = runBlocking {
        val epubA = epub("a.epub")
        val epubB = epub("b.epub")
        val coverShared = tmp.newFile("shared.jpg").apply { writeText("cover") }
        val coverSolo = tmp.newFile("solo.jpg").apply { writeText("cover") }
        val repo = FakeBookRepository(
            listOf(
                testBook("a", filePath = epubA, coverPath = coverShared.absolutePath),
                testBook("b", filePath = epubB, coverPath = coverShared.absolutePath),
                testBook("c", filePath = epub("c.epub"), coverPath = coverSolo.absolutePath)
            )
        )
        val mgr = CollectionManager(repo)

        // "a" shares its cover with "b": epub goes, shared cover stays.
        mgr.removeBook("a")
        assertEquals(listOf("b", "c"), repo.current().map { it.id })
        assertFalse(java.io.File(epubA).exists())
        assertTrue(coverShared.exists())

        // "c" owns its cover alone: both go.
        mgr.removeBook("c")
        assertEquals(listOf("b"), repo.current().map { it.id })
        assertTrue(java.io.File(epubB).exists())
        assertFalse(coverSolo.exists())
    }

    @Test
    fun togglePrivate_togglesFlag() = runBlocking {
        val repo = FakeBookRepository(listOf(testBook("a")))
        val mgr = CollectionManager(repo)
        mgr.togglePrivate("a")
        assertTrue(repo.current().single().isPrivate)
        mgr.togglePrivate("a")
        assertFalse(repo.current().single().isPrivate)
    }
}
