package com.novelfa.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup format guards (plan §9.6): the integrity algorithm is shared
 * byte-for-byte with the Windows app, so its canonical string is pinned
 * here. If this test needs a new expected hash, the format changed —
 * bump formatVersion on BOTH platforms instead of just updating the test.
 */
class BackupServiceTest {

    @Test
    fun integrity_matchesPinnedCanonicalForm() {
        val hash = BackupService.computeIntegrity(
            pathHashes = mapOf("fonts/001.ttf" to "aa", "avatar.jpg" to "bb"),
            profileName = "Aster",
            maxRank = 4,
            finishedBooks = 27,
            secretThemes = listOf("emt", "beako") // intentionally unsorted
        )
        // canonical: "avatar.jpg:bb\nfonts/001.ttf:aa\nprofile:Aster|4|27|beako,emt"
        assertEquals(
            "8f7a539265549949b5014c465f1ff5a1950f5e3bd57e71da77697091a154f03e",
            hash
        )
    }

    @Test
    fun integrity_emptyBackupHasOnlyProfileLine() {
        val hash = BackupService.computeIntegrity(
            pathHashes = emptyMap(),
            profileName = "",
            maxRank = -1,
            finishedBooks = 0,
            secretThemes = emptyList()
        )
        assertEquals(
            BackupService.sha256Hex("profile:|-1|0|".toByteArray(Charsets.UTF_8)),
            hash
        )
    }

    @Test
    fun fontMagic_acceptsRealFontHeaders() {
        assertTrue(BackupService.isFontMagic(byteArrayOf(0, 1, 0, 0)))
        assertTrue(BackupService.isFontMagic("OTTO".toByteArray(Charsets.US_ASCII)))
        assertTrue(BackupService.isFontMagic("true".toByteArray(Charsets.US_ASCII)))
        assertTrue(BackupService.isFontMagic("ttcf".toByteArray(Charsets.US_ASCII)))
        assertFalse(BackupService.isFontMagic("PK\u0003\u0004".toByteArray(Charsets.US_ASCII)))
        assertFalse(BackupService.isFontMagic(byteArrayOf(0, 1)))
    }

    @Test
    fun fontBaseName_sanitizesAndFallsBack() {
        assertEquals("My Font", BackupService.fontBaseName("My Font", "Fam", "x.ttf"))
        assertEquals("Fam", BackupService.fontBaseName("a/b", "Fam", "x.ttf"))
        assertEquals("orig", BackupService.fontBaseName("..", "<bad>", "orig.ttf"))
        assertEquals("font", BackupService.fontBaseName("", "", ""))
    }
}
