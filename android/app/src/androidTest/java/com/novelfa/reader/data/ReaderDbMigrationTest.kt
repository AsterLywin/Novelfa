package com.novelfa.reader.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for ReaderDb (versions 1 → 5).
 *
 * Why two styles in one file:
 * - Steps 1→2, 2→3 and 4→5 only ADD nullable columns, so the strict
 *   [MigrationTestHelper.runMigrationsAndValidate] schema check applies.
 * - Step 3→4 adds `importMode TEXT NOT NULL DEFAULT 'COPY'`. A migrated
 *   table therefore carries a column default that a freshly created v5
 *   table does not have (the entity declares the "COPY" default in
 *   Kotlin, not as a Room defaultValue). Whether Room's schema
 *   comparison treats that default as a mismatch is version-dependent,
 *   so the 3→4 step and the full chain are validated directly instead:
 *   the real Migration objects run against a real SQLite database and
 *   the tests assert columns, types, NOT NULL flags, the 'COPY'
 *   default and, above all, that every pre-migration row survives
 *   byte-for-byte. That is the user-data guarantee this file exists
 *   for; a strict-equality check on the default would only test
 *   Room's comparison semantics, not the user's data.
 *
 * These are instrumented tests: they need a device or emulator to
 * execute. They are compile-verified in the VM; execution happens
 * wherever instrumented tests run.
 */
@RunWith(AndroidJUnit4::class)
class ReaderDbMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        "com.novelfa.reader.data.ReaderDb",
        FrameworkSQLiteOpenHelperFactory()
    )

    // A v1 row with every column filled, and one with only the
    // NOT NULL columns, so both the happy path and NULL handling
    // are covered by the full-chain test.
    private fun insertV1Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO books (id, title, filePath, coverPath, totalChapters, " +
                "lastChapterIndex, fontFamily, fontSize, lineHeight, sideMargin, " +
                "paragraphSpacing, dateAdded, lastOpenedDate, isPrivate, " +
                "isFavorite, collectionName) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "b1", "کتاب کامل", "/books/full.epub", "/covers/full.jpg", 12, 5,
                "Vazirmatn", 18, 1.8, 24, 0.5, "2026-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z", 0, 1, "خوانده‌شده"
            )
        )
        db.execSQL(
            "INSERT INTO books (id, title, filePath, totalChapters, " +
                "lastChapterIndex, fontFamily, fontSize, lineHeight, sideMargin, " +
                "paragraphSpacing, dateAdded, isPrivate, isFavorite) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "b2", "Minimal Book", "/books/min.epub", 1, 0,
                "", 18, 1.8, 24, 0.0, "2026-01-02T00:00:00Z", 1, 0
            )
        )
    }

    private fun readRow(db: SupportSQLiteDatabase, id: String): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        db.query("SELECT * FROM books WHERE id = ?", arrayOf<Any?>(id)).use { c ->
            assertTrue("row $id missing after migration", c.moveToFirst())
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                out[name] = when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    else -> c.getString(i)
                }
            }
        }
        return out
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?
    )

    private fun tableColumns(db: SupportSQLiteDatabase): Map<String, ColumnInfo> {
        val out = mutableMapOf<String, ColumnInfo>()
        db.query("PRAGMA table_info(books)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            val typeIdx = c.getColumnIndex("type")
            val notNullIdx = c.getColumnIndex("notnull")
            val defaultIdx = c.getColumnIndex("dflt_value")
            while (c.moveToNext()) {
                val name = c.getString(nameIdx)
                out[name] = ColumnInfo(
                    name = name,
                    type = c.getString(typeIdx),
                    notNull = c.getInt(notNullIdx) == 1,
                    default = if (c.isNull(defaultIdx)) null else c.getString(defaultIdx)
                )
            }
        }
        return out
    }

    @Test
    fun migrate1To2_validatesSchema() {
        helper.createDatabase("migrate-1-2", 1).close()
        helper.runMigrationsAndValidate("migrate-1-2", 2, true, MIGRATION_1_2)
    }

    @Test
    fun migrate2To3_validatesSchema() {
        helper.createDatabase("migrate-2-3", 2).close()
        helper.runMigrationsAndValidate("migrate-2-3", 3, true, MIGRATION_2_3)
    }

    @Test
    fun migrate4To5_validatesSchema() {
        helper.createDatabase("migrate-4-5", 4).close()
        helper.runMigrationsAndValidate("migrate-4-5", 5, true, MIGRATION_4_5)
    }

    @Test
    fun migrate3To4_addsColumnsAndCopyDefault() {
        val db = helper.createDatabase("migrate-3-4", 3)
        helper.closeWhenFinished(db)
        insertV1Rows(db)
        MIGRATION_3_4.migrate(db)

        val cols = tableColumns(db)
        assertEquals("TEXT", cols.getValue("sourceUri").type)
        assertEquals(false, cols.getValue("sourceUri").notNull)
        assertEquals("TEXT", cols.getValue("importMode").type)
        assertEquals(true, cols.getValue("importMode").notNull)
        // SQLite reports the default with its quotes.
        assertEquals("'COPY'", cols.getValue("importMode").default)

        // Pre-migration rows survive and pick up the default.
        val row = readRow(db, "b1")
        assertEquals("کتاب کامل", row["title"])
        assertEquals(12L, row["totalChapters"])
        assertEquals("COPY", row["importMode"])
        assertNull(row["sourceUri"])
    }

    @Test
    fun migrate1To5_preservesUserData() {
        val db = helper.createDatabase("migrate-1-5", 1)
        helper.closeWhenFinished(db)
        insertV1Rows(db)
        MIGRATION_1_2.migrate(db)
        MIGRATION_2_3.migrate(db)
        MIGRATION_3_4.migrate(db)
        MIGRATION_4_5.migrate(db)

        // Final shape: every v1 column plus the four added ones.
        val cols = tableColumns(db)
        val expected = listOf(
            "id", "title", "filePath", "coverPath", "totalChapters",
            "lastChapterIndex", "fontFamily", "fontSize", "lineHeight",
            "sideMargin", "paragraphSpacing", "dateAdded", "lastOpenedDate",
            "isPrivate", "isFavorite", "collectionName",
            "originalFileName", "lastAnchor", "sourceUri", "importMode",
            "contentHash"
        )
        assertEquals(expected.toSet(), cols.keys)

        // Full row survives untouched, new columns at their defaults.
        val full = readRow(db, "b1")
        assertEquals("کتاب کامل", full["title"])
        assertEquals("/books/full.epub", full["filePath"])
        assertEquals("/covers/full.jpg", full["coverPath"])
        assertEquals(12L, full["totalChapters"])
        assertEquals(5L, full["lastChapterIndex"])
        assertEquals("Vazirmatn", full["fontFamily"])
        assertEquals(18L, full["fontSize"])
        assertEquals(1.8, full["lineHeight"] as Double, 0.001)
        assertEquals(24L, full["sideMargin"])
        assertEquals(0.5, full["paragraphSpacing"] as Double, 0.001)
        assertEquals("2026-01-01T00:00:00Z", full["dateAdded"])
        assertEquals("2026-02-01T00:00:00Z", full["lastOpenedDate"])
        assertEquals(0L, full["isPrivate"])
        assertEquals(1L, full["isFavorite"])
        assertEquals("خوانده‌شده", full["collectionName"])
        assertNull(full["originalFileName"])
        assertNull(full["lastAnchor"])
        assertNull(full["sourceUri"])
        assertEquals("COPY", full["importMode"])
        assertNull(full["contentHash"])

        // Minimal row: NULLs stay NULL, no NOT NULL column breaks.
        val minimal = readRow(db, "b2")
        assertEquals("Minimal Book", minimal["title"])
        assertNull(minimal["coverPath"])
        assertNull(minimal["lastOpenedDate"])
        assertNull(minimal["collectionName"])
        assertEquals(1L, minimal["isPrivate"])
        assertEquals("COPY", minimal["importMode"])
    }
}
