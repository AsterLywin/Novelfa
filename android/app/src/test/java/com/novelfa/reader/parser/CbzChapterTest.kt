package com.novelfa.reader.parser

import java.io.File
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the CBZ/ZIP chapter list
 * ([CbzBook.chaptersForEntries]): folders become chapters in
 * reading order, and selecting one must land on its first page.
 * The viewer itself (dialog, jump, zoom, slider, mode) is Compose
 * and cannot run in local unit tests; it reuses the slider's jump
 * path, so a chapter jump is exactly a slider jump to the
 * chapter's first page index tested here, and device testing
 * confirms the dialog on a real book.
 */
class CbzChapterTest {

    @Test
    fun flatArchive_noChapters() {
        val chapters = CbzBook.chaptersForEntries(
            listOf("001.jpg", "002.jpg", "010.jpg")
        )
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun folderedArchive_chaptersInNaturalOrder() {
        val chapters = CbzBook.chaptersForEntries(
            listOf(
                "Ch10/001.jpg",
                "Ch2/002.jpg",
                "Ch2/001.jpg",
                "Ch1/001.jpg"
            )
        )
        assertEquals(3, chapters.size)
        assertEquals("Ch1", chapters[0].title)
        assertEquals(0, chapters[0].firstPageIndex)
        assertEquals("Ch2", chapters[1].title)
        assertEquals(1, chapters[1].firstPageIndex)
        assertEquals("Ch10", chapters[2].title)
        assertEquals(3, chapters[2].firstPageIndex)
    }

    @Test
    fun singleFolder_noChapters() {
        val chapters = CbzBook.chaptersForEntries(
            listOf("Chapter 01/001.jpg", "Chapter 01/002.jpg")
        )
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun sharedSeriesFolder_strippedFromTitles() {
        val chapters = CbzBook.chaptersForEntries(
            listOf(
                "My Manhwa/Chapter 02/001.jpg",
                "My Manhwa/Chapter 01/001.jpg",
                "My Manhwa/Chapter 01/002.jpg"
            )
        )
        assertEquals(2, chapters.size)
        assertEquals("Chapter 01", chapters[0].title)
        assertEquals(0, chapters[0].firstPageIndex)
        assertEquals("Chapter 02", chapters[1].title)
        assertEquals(2, chapters[1].firstPageIndex)
    }

    @Test
    fun nestedSubfolders_stayInParentChapter() {
        val chapters = CbzBook.chaptersForEntries(
            listOf(
                "Vol 1/Ch 1/part 1/001.jpg",
                "Vol 1/Ch 1/part 2/001.jpg",
                "Vol 1/Ch 2/001.jpg"
            )
        )
        assertEquals(2, chapters.size)
        assertEquals("Ch 1", chapters[0].title)
        assertEquals(0, chapters[0].firstPageIndex)
        assertEquals("Ch 2", chapters[1].title)
        assertEquals(2, chapters[1].firstPageIndex)
    }

    @Test
    fun rootCoverPages_chapterStartsPastZero() {
        // The cover sorts before the folders in natural order
        // (its leading digits sort before any folder name), so it
        // is page 0 and the first folder chapter starts at 1.
        val chapters = CbzBook.chaptersForEntries(
            listOf(
                "000-cover.jpg",
                "Ch01/001.jpg",
                "Ch02/001.jpg"
            )
        )
        assertEquals(2, chapters.size)
        assertEquals("Ch01", chapters[0].title)
        assertEquals(1, chapters[0].firstPageIndex)
        assertEquals("Ch02", chapters[1].title)
        assertEquals(2, chapters[1].firstPageIndex)
    }

    @Test
    fun nonImageEntries_ignored() {
        val chapters = CbzBook.chaptersForEntries(
            listOf(
                "ComicInfo.xml",
                "__MACOSX/Ch01/._001.jpg",
                "Ch01/.hidden.jpg",
                "Ch01/001.jpg",
                "Ch02/001.jpg",
                "notes.txt"
            )
        )
        assertEquals(2, chapters.size)
        assertEquals("Ch01", chapters[0].title)
        assertEquals(0, chapters[0].firstPageIndex)
        assertEquals("Ch02", chapters[1].title)
        assertEquals(1, chapters[1].firstPageIndex)
    }

    @Test
    fun realZipFile_chaptersFromArchiveListing() {
        val zipFile = File.createTempFile("manhwa", ".cbz")
        try {
            ZipOutputStream(zipFile.outputStream()).use { out ->
                // Deliberately out of order in the archive: the
                // chapter list must still come out in reading order.
                for (name in listOf("Ch10/001.jpg", "Ch2/001.jpg", "Ch1/001.jpg", "Ch1/002.jpg")) {
                    out.putNextEntry(java.util.zip.ZipEntry(name))
                    out.write(byteArrayOf(0))
                    out.closeEntry()
                }
            }
            val chapters = CbzBook.chapters(zipFile)
            assertEquals(3, chapters.size)
            assertEquals("Ch1", chapters[0].title)
            assertEquals(0, chapters[0].firstPageIndex)
            assertEquals("Ch2", chapters[1].title)
            assertEquals(2, chapters[1].firstPageIndex)
            assertEquals("Ch10", chapters[2].title)
            assertEquals(3, chapters[2].firstPageIndex)
        } finally {
            zipFile.delete()
        }
    }
}
