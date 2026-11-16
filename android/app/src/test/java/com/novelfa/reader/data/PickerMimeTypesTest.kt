package com.novelfa.reader.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app pickers' MIME filter ([SUPPORTED_BOOK_MIME_TYPES]):
 * the entries that decide which files a user can actually select.
 * The octet-stream entry is the load-bearing one for comic
 * archives — see its comment in Importer.kt: Android's MimeTypeMap
 * has no cbz/cbr/fb2/fbz entry, so the system picker reports those
 * files as application/octet-stream and greys them out without it.
 * A regression here silently re-breaks CBZ import for every user,
 * with the importer itself never seeing the file.
 */
class PickerMimeTypesTest {

    @Test
    fun octetStreamPresent_soCbzFilesAreSelectable() {
        assertTrue(
            "application/octet-stream must be in the picker filter: " +
                "the system reports .cbz files as octet-stream",
            "application/octet-stream" in SUPPORTED_BOOK_MIME_TYPES
        )
    }

    @Test
    fun comicArchiveTypesPresent() {
        val types = SUPPORTED_BOOK_MIME_TYPES.toSet()
        assertTrue("application/zip" in types)
        assertTrue("application/x-cbz" in types)
        assertTrue("application/vnd.comicbook+zip" in types)
        assertTrue("application/x-cbr" in types)
        assertTrue("application/vnd.comicbook-rar" in types)
    }

    @Test
    fun noDuplicateMimeTypes() {
        val types = SUPPORTED_BOOK_MIME_TYPES.toList()
        assertTrue(types.size == types.toSet().size)
    }
}
