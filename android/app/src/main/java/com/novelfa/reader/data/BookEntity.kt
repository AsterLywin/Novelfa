package com.novelfa.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.floor
import kotlin.math.min

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val filePath: String,
    val coverPath: String? = null,
    val totalChapters: Int,
    val lastChapterIndex: Int = 0,
    val fontFamily: String = "Tahoma",
    val fontSize: Int = 18,
    val lineHeight: Double = 0.0,
    val sideMargin: Int = 0,
    val paragraphSpacing: Double = 0.0,
    val dateAdded: String,
    val lastOpenedDate: String? = null,
    val isPrivate: Boolean = false,
    val isFavorite: Boolean = false,
    val collectionName: String? = null,
    /** Original file name from the import picker (for display disambiguation). */
    val originalFileName: String? = null
)

fun progressPercent(e: BookEntity): Double {
    if (e.totalChapters <= 0 || e.lastOpenedDate.isNullOrEmpty()) {
        return 0.0
    }
    return min(100.0, (e.lastChapterIndex + 1) * 100.0 / e.totalChapters.toDouble())
}

fun isCompleted(e: BookEntity): Boolean =
    e.totalChapters > 0 && e.lastChapterIndex + 1 >= e.totalChapters && !e.lastOpenedDate.isNullOrEmpty()

fun isInProgress(e: BookEntity): Boolean =
    !e.lastOpenedDate.isNullOrEmpty() && !isCompleted(e)

fun percentDisplay(e: BookEntity): Int =
    if (isCompleted(e)) 100 else min(99.0, floor(progressPercent(e))).toInt()
