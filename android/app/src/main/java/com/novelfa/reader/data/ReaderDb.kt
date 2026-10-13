package com.novelfa.reader.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BookEntity::class], version = 1, exportSchema = true)
abstract class ReaderDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
