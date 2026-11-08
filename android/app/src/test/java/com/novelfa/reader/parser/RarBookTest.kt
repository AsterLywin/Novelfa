package com.novelfa.reader.parser

import com.novelfa.reader.data.InPlaceSource
import com.novelfa.reader.parser.engine.RarEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-Kotlin tests for CBR/RAR support. No test here calls the
 * native bridge: there is no .so on the test classpath, so
 * everything native is covered by the graceful-degradation
 * decision (the engine reports unavailable) and by the pure
 * helpers around it — detection from hand-built header bytes,
 * entry filtering/ordering/chapters from fake [RarEntry] lists,
 * and the in-place sniff. A real RAR file is a device test, not
 * a unit test.
 */
class RarBookTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Minimal RAR headers, built by hand: magic `Rar!\x1a\x07`
    // plus the version byte at offset 6 (0 = RAR4, 1 = RAR5).
    private fun rarFile(name: String, versionByte: Int): File {
        val f = tempFolder.newFile(name)
        val head = byteArrayOf(
            'R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(),
            '!'.code.toByte(), 0x1A, 0x07, versionByte.toByte(), 0x00
        )
        f.writeBytes(head)
        return f
    }

    private fun entry(
        name: String,
        isDirectory: Boolean = false,
        isSolid: Boolean = false
    ) = RarEntry(name, 100, 0, isSolid, isDirectory)

    // --- Detection: magic + version byte ---

    @Test
    fun sniff_rar4Magic_isCbr_version4() {
        val f = rarFile("mystery", 0)
        assertEquals(BookFormat.CBR, BookFormat.detectStrongContent(f))
        assertEquals(BookFormat.CBR, BookFormat.detect(f))
        assertEquals(4, BookFormat.rarVersion(f))
        assertTrue(BookFormat.isRarMagic(f.readBytes(), 8))
    }

    @Test
    fun sniff_rar5Magic_isCbr_version5() {
        val f = rarFile("mystery", 1)
        assertEquals(BookFormat.CBR, BookFormat.detectStrongContent(f))
        assertEquals(BookFormat.CBR, BookFormat.detect(f))
        assertEquals(5, BookFormat.rarVersion(f))
    }

    @Test
    fun sniff_rarNamedRar_keepsRarExtension() {
        val f = rarFile("book.rar", 1)
        assertEquals(BookFormat.RAR, BookFormat.detectStrongContent(f))
        assertEquals(BookFormat.RAR, BookFormat.detect(f))
        assertEquals(5, BookFormat.rarVersion(f))
    }

    @Test
    fun sniff_rarNamedCbr_isCbr() {
        val f = rarFile("book.cbr", 0)
        assertEquals(BookFormat.CBR, BookFormat.detectStrongContent(f))
        assertEquals(4, BookFormat.rarVersion(f))
    }

    @Test
    fun sniff_zipWearingCbr_isNotRar() {
        // A real ZIP's PK header is not a RAR magic: the version
        // is 0 and strong sniffing stays silent, so the .cbr
        // extension still claims it — and the importer then
        // rejects it with the rename-to-.cbz message, not as a
        // corrupt RAR.
        val f = tempFolder.newFile("book.cbr")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("001.jpg"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }
        assertEquals(0, BookFormat.rarVersion(f))
        assertFalse(BookFormat.isRarMagic(f.readBytes(), 8))
        assertNull(BookFormat.detectStrongContent(f))
        assertEquals(BookFormat.CBR, BookFormat.detect(f))
    }

    @Test
    fun sniff_plainBytes_areNotRar() {
        val f = tempFolder.newFile("mystery")
        f.writeBytes(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'x'.code.toByte()))
        assertEquals(0, BookFormat.rarVersion(f))
        assertNull(BookFormat.detectStrongContent(f))
    }

    @Test
    fun sniff_shortFile_isNotRar() {
        val f = tempFolder.newFile("mystery")
        f.writeBytes(byteArrayOf('R'.code.toByte(), 'a'.code.toByte()))
        assertEquals(0, BookFormat.rarVersion(f))
        assertFalse(BookFormat.isRarMagic(f.readBytes(), 2))
    }

    @Test
    fun sniff_inPlaceHead_rarMagic() {
        // Register-only in-place imports sniff from the head
        // sample, not a File: the same magic must claim CBR
        // there, and keep RAR when the picked name says .rar.
        val head = byteArrayOf(
            'R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(),
            '!'.code.toByte(), 0x1A, 0x07, 0x01, 0x00
        )
        assertEquals(BookFormat.CBR, InPlaceSource.sniffFormat(head, head.size, "book.cbr"))
        assertEquals(BookFormat.RAR, InPlaceSource.sniffFormat(head, head.size, "book.rar"))
        assertEquals(BookFormat.CBR, InPlaceSource.sniffFormat(head, head.size, "mystery"))
    }

    // --- Entry filtering / ordering / chapters (fake listings) ---

    @Test
    fun imageEntries_filterAndNaturalOrder() {
        val names = RarEngine.imageEntryNames(
            listOf(
                entry("page-10.jpg"),
                entry("ComicInfo.xml"),
                entry("__MACOSX/junk.jpg"),
                entry(".hidden.jpg"),
                entry("folder/"),
                entry("page-2.jpg", isDirectory = true),
                entry("page-2.jpg"),
                entry("cover.avif"),
                entry("notes.txt")
            )
        )
        assertEquals(listOf("cover.avif", "page-2.jpg", "page-10.jpg"), names)
    }

    @Test
    fun imageEntries_emptyWhenNoImages() {
        val names = RarEngine.imageEntryNames(
            listOf(entry("readme.txt"), entry("data.bin"))
        )
        assertTrue(names.isEmpty())
    }

    @Test
    fun chapters_folderedArchive_splitsLikeCbz() {
        val chapters = RarEngine.chapters(
            listOf(
                entry("Ch10/001.jpg"),
                entry("Ch2/002.jpg"),
                entry("Ch2/001.jpg"),
                entry("Ch1/001.jpg")
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
    fun chapters_flatArchive_noChapters() {
        val chapters = RarEngine.chapters(
            listOf(entry("001.jpg"), entry("002.jpg"), entry("010.jpg"))
        )
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun chapters_solidEntries_stillCount() {
        // Solid archives list the same way: the solid flag
        // affects extraction, never the page list.
        val names = RarEngine.imageEntryNames(
            listOf(entry("001.jpg", isSolid = true), entry("002.jpg", isSolid = true))
        )
        assertEquals(listOf("001.jpg", "002.jpg"), names)
    }

    // --- Graceful degradation: no .so on the test classpath ---

    @Test
    fun engine_unavailable_withoutNativeLib() {
        assumeTrue(!RarNative.available)
        assertFalse(RarEngine.isAvailable())
        assertNull(RarEngine.listEntries(tempFolder.newFile("book.cbr")))
        assertNull(
            RarEngine.readEntryBytes(
                tempFolder.newFile("book2.cbr"), entry("001.jpg")
            )
        )
        assertEquals(0, RarEngine.rarVersion(tempFolder.newFile("book3.cbr")))
    }

    @Test
    fun metadata_unavailable_throwsInsteadOfCrashing() {
        assumeTrue(!RarNative.available)
        val f = rarFile("book.cbr", 1)
        try {
            RarEngine.metadata(f, "book.cbr", tempFolder.newFolder())
            throw AssertionError("metadata must throw without the native lib")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unavailable"))
        }
    }

    // --- Misnamed files: the content decides, not the name ---
    // A RAR renamed to .cbz (seen in the wild) must still be
    // detected as a RAR: the import sniffs it, but the open
    // paths re-detect from the file, and in-place books keep
    // their original name — so detection must work on content
    // alone. BookFormat.detect() itself stays extension-first
    // by design; the open paths use detectStrongContent /
    // archiveKind instead (see PdfReaderRoute, openParsed).

    @Test
    fun misnamed_rar4WearingCbz_isDetectedAsCbr() {
        val f = rarFile("comic.cbz", 0)
        assertEquals(BookFormat.CBR, BookFormat.detectStrongContent(f))
        assertEquals("RAR", CbzBook.archiveKind(f))
        assertEquals(4, BookFormat.rarVersion(f))
    }

    @Test
    fun misnamed_rar5WearingCbz_isDetectedAsCbr() {
        val f = rarFile("comic.cbz", 1)
        assertEquals(BookFormat.CBR, BookFormat.detectStrongContent(f))
        assertEquals("RAR", CbzBook.archiveKind(f))
        assertEquals(5, BookFormat.rarVersion(f))
    }

    @Test
    fun misnamed_zipWearingCbr_isZipByArchiveKind() {
        val f = tempFolder.newFile("comic.cbr")
        f.writeBytes(
            byteArrayOf(
                'P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04, 0, 0, 0, 0
            )
        )
        assertEquals("ZIP", CbzBook.archiveKind(f))
        // A plain ZIP has no strong content claim: the open path
        // decides by archiveKind, not by sniffing.
        assertNull(BookFormat.detectStrongContent(f))
    }

    @Test
    fun detect_misnamedRar_stillExtensionFirst() {
        // Locks in detect()'s documented order: extension first.
        // The open paths must NOT rely on detect() alone for a
        // possibly-misnamed file.
        val f = rarFile("comic.cbz", 0)
        assertEquals(BookFormat.CBZ, BookFormat.detect(f))
    }
}
