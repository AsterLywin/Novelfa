package com.novelfa.reader.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class], version = 2, exportSchema = true)
abstract class ReaderDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

/**
 * 1 → 2: adds the nullable `originalFileName` column (used to disambiguate
 * duplicate/generic book titles). Nullable with no default, so existing rows
 * migrate untouched.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN originalFileName TEXT")
    }
}
