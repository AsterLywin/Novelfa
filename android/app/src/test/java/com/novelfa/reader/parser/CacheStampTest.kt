package com.novelfa.reader.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheStampTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sameSizeSameMtime_contentChange_invalidatesStamp() {
        val cache = tempFolder.newFolder("cache")
        val source = tempFolder.newFile("book.epub")
        source.writeText("AAAAAAAAAA")
        val mtime = source.lastModified()
        CacheStamp.writeStamp(cache, source)
        assertTrue(CacheStamp.isValidStamp(cache, source))
        // Same length, same mtime, different content (a backup restore
        // or a same-size edit): the old mtime|length stamp would have
        // trusted this stale cache.
        source.writeText("BBBBBBBBBB")
        source.setLastModified(mtime)
        assertFalse(CacheStamp.isValidStamp(cache, source))
    }

    @Test
    fun unchangedFile_stampStaysValid() {
        val cache = tempFolder.newFolder("cache2")
        val source = tempFolder.newFile("book2.epub")
        source.writeText("hello world")
        CacheStamp.writeStamp(cache, source)
        assertTrue(CacheStamp.isValidStamp(cache, source))
        assertEquals(
            CacheStamp.stampFor(source),
            File(cache, CacheStamp.STAMP_FILE_NAME).readText(Charsets.UTF_8)
        )
    }

    @Test
    fun missingStamp_isInvalid() {
        val cache = tempFolder.newFolder("cache3")
        val source = tempFolder.newFile("book3.epub")
        source.writeText("hello")
        assertFalse(CacheStamp.isValidStamp(cache, source))
    }
}
