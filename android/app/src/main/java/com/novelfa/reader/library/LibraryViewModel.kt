package com.novelfa.reader.library

import android.net.Uri
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.SortFa
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.data.isInProgress
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Library tabs. Predicates mirror WPF Book.cs: IsCompleted / IsInProgress. */
enum class Tab {
    ALL,
    READING,
    COMPLETED,
    RECENT,
    PRIVATE
}

/**
 * Display name rule ported from WPF LibraryService.AssignDisplayTitles:
 * books sharing one title (case-insensitive, trimmed) — or carrying a generic
 * title such as "unknown"/"untitled"/"کتاب ترجمه شده" — show the file name
 * (without extension) so duplicates stay distinguishable.
 */
fun displayTitle(book: BookEntity, all: List<BookEntity>): String {
    val t = book.title.trim()
    val duplicate = all.count { it.title.trim().equals(t, ignoreCase = true) } > 1
    if ((duplicate || isGenericTitle(t)) && book.filePath.isNotEmpty()) {
        val name = File(book.filePath).nameWithoutExtension
        if (name.isNotBlank()) return name
    }
    return book.title
}

private fun isGenericTitle(title: String): Boolean {
    if (title.isBlank()) return true
    val t = title.trim().replace("‌", " ")
    return t == "کتاب ترجمه شده" ||
        t.equals("unknown", ignoreCase = true) ||
        t.equals("untitled", ignoreCase = true)
}

/**
 * Thin ViewModel over [BookDao] + [Importer].
 *
 * [importer] is nullable only so pure-JVM unit tests can construct this class
 * without an Android Context; production code (NavGraph) always passes a real
 * Importer and [import] delegates straight to it.
 */
class LibraryViewModel(
    val dao: BookDao,
    private val importer: Importer? = null,
    val pinGate: PinGate = NoPinGate
) {
    val books: Flow<List<BookEntity>> = dao.allBooks()

    fun filtered(tab: Tab, query: String): Flow<List<BookEntity>> =
        dao.allBooks().map { applyFilter(it, tab, query) }

    fun byCollection(name: String): Flow<List<BookEntity>> = dao.byCollection(name)

    suspend fun import(uri: Uri): String =
        requireNotNull(importer) { "Importer not provided" }.importEpub(uri)

    suspend fun toggleFavorite(book: BookEntity) {
        dao.update(book.copy(isFavorite = !book.isFavorite))
    }

    suspend fun setCollection(book: BookEntity, name: String?) {
        dao.update(book.copy(collectionName = name?.takeIf { it.isNotBlank() }))
    }
}

internal fun applyFilter(
    all: List<BookEntity>,
    tab: Tab,
    query: String
): List<BookEntity> {
    var list = if (tab == Tab.PRIVATE) {
        all.filter { it.isPrivate }
    } else {
        val visible = all.filter { !it.isPrivate }
        when (tab) {
            Tab.READING -> visible.filter { isInProgress(it) }
            Tab.COMPLETED -> visible.filter { isCompleted(it) }
            Tab.RECENT -> visible
                .filter { !it.lastOpenedDate.isNullOrEmpty() }
                .sortedByDescending { it.lastOpenedDate }
            Tab.ALL, Tab.PRIVATE -> visible
        }
    }
    val q = query.trim()
    if (q.isNotEmpty()) {
        list = list.filter {
            it.title.contains(q, ignoreCase = true) ||
                displayTitle(it, all).contains(q, ignoreCase = true)
        }
    }
    // RECENT keeps lastOpenedDate-desc order (WPF default sort); other tabs
    // sort by the DisplayTitle rule with Persian natural comparison.
    return if (tab == Tab.RECENT) {
        list
    } else {
        list.sortedWith { a, b -> SortFa.compare(displayTitle(a, all), displayTitle(b, all)) }
    }
}
