package com.novelfa.reader.domain

import kotlin.math.floor
import kotlin.math.min

/**
 * A library book as the UI layer sees it. Field-for-field identical
 * to the Room [com.novelfa.reader.data.BookEntity], but free of any
 * persistence type: screens, ViewModels and helpers depend on this,
 * never on the entity. Mapping lives in
 * [com.novelfa.reader.data.BookMapping] (data knows both sides;
 * domain must not import data).
 */
data class Book(
    val id: String,
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
     * Paged-mode text-point anchor ("li:tok") within [lastChapterIndex];
     * null = chapter start. Saved on paged turns (throttled) and restored
     * on reopen / same-chapter reload.
     */
    val lastAnchor: String? = null,
    /**
     * In-place import: the Uri the book was picked from, kept so the
     * reader can re-open the user's own file instead of a copy. Null
     * for copy-imported books and for in-place books picked by path,
     * where [filePath] itself IS the external file.
     */
    val sourceUri: String? = null,
    /**
     * How the book entered the library: "COPY" (the stored file in
     * filesDir/books, the default) or "IN_PLACE" (the user's own file,
     * read where it lives). Stored as a plain string so Room needs no
     * type converter.
     */
    val importMode: String = "COPY",
    /**
     * SHA-256 of the book's content, when known. Null means "not yet
     * known", never "no hash": callers must fall back to hashing the
     * file, exactly as before.
     */
    val contentHash: String? = null
)

fun progressPercent(book: Book): Double {
    if (book.totalChapters <= 0 || book.lastOpenedDate.isNullOrEmpty()) {
        return 0.0
    }
    return min(100.0, (book.lastChapterIndex + 1) * 100.0 / book.totalChapters.toDouble())
}

fun isCompleted(book: Book): Boolean =
    book.totalChapters > 0 && book.lastChapterIndex + 1 >= book.totalChapters && !book.lastOpenedDate.isNullOrEmpty()

fun isInProgress(book: Book): Boolean =
    !book.lastOpenedDate.isNullOrEmpty() && !isCompleted(book)

fun percentDisplay(book: Book): Int =
    if (isCompleted(book)) 100 else min(99.0, floor(progressPercent(book))).toInt()
