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
    val fontFamily: String = "",
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
    val originalFileName: String? = null,
    /**
     * Paged-mode text-point anchor ("li:tok", see rdrCurrentAnchor)
     * within [lastChapterIndex]; null = chapter start. Saved on
     * paged turns (throttled) and restored on reopen / same-chapter
     * reload, so the reader lands on the same sentence even after
     * repagination changes every page boundary.
     */
    val lastAnchor: String? = null
)

/**
 * True when a stored title is just the internal storage UUID
 * (36-char hex-with-dashes). That happens when a converted book
 * (TXT/Markdown/DOCX/PDF) had no internal title and its parser fell
 * back to the stored file's name, which is "$uuid.<ext>" — the
 * original file name lives in [BookEntity.originalFileName], not in
 * the title. Callers must never show a UUID-like title when a real
 * file name is available.
 */
fun isUuidLikeTitle(s: String): Boolean {
    val t = s.trim().lowercase()
    if (t.length != 36) return false
    return t.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
}

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
