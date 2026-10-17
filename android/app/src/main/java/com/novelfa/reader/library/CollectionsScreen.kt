package com.novelfa.reader.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.novelfa.reader.R
import com.novelfa.reader.data.BookEntity
import kotlinx.coroutines.launch

/**
 * Collections basics ported from WPF CollectionWindow: distinct collection
 * names with counts, tap to open the filtered grid, long-press a card to
 * assign it to a collection, star toggle for favorites.
 *
 * Task 8 extension: rename/delete collection dialogs + per-book
 * reset-progress + remove-book confirmations (via LibraryViewModel, which
 * delegates to CollectionManager).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionsScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val books by viewModel.books.collectAsState(initial = emptyList())
    val collections = remember(books) {
        books.mapNotNull { it.collectionName?.takeIf { n -> n.isNotBlank() } }
            .distinct()
            .sorted()
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var assignTarget by remember { mutableStateOf<BookEntity?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var resetTarget by remember { mutableStateOf<BookEntity?>(null) }
    var removeTarget by remember { mutableStateOf<BookEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column {
        if (onBack != null) {
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = onBack) { Text("→") }
                Text(
                    stringResource(R.string.library_collections),
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
        if (selected == null) {
            LazyColumn {
                items(collections) { name ->
                    val count = books.count { it.collectionName == name }
                    Card(
                        onClick = { selected = name },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, modifier = Modifier.weight(1f))
                            Text("$count")
                            TextButton(onClick = { renameTarget = name }) {
                                Text(stringResource(R.string.rename))
                            }
                            TextButton(onClick = { deleteTarget = name }) {
                                // No generic "delete" Loc key; hardcoded fa (Task 7 precedent).
                                Text("حذف")
                            }
                        }
                    }
                }
            }
        } else {
            val name = selected!!
            val inCollection by viewModel.byCollection(name).collectAsState(initial = emptyList())
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                items(inCollection, key = { it.id }) { book ->
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { assignTarget = book }
                        )
                    ) {
                        BookCard(
                            book = book,
                            title = displayTitle(book, books),
                            onClick = { onOpenBook(book.id) }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { scope.launch { viewModel.toggleFavorite(book) } }
                            ) {
                                Text(if (book.isFavorite) "★" else "☆")
                            }
                            TextButton(onClick = { resetTarget = book }) {
                                // "از اول" (start over), like the WPF restart prompt.
                                Text("از اول")
                            }
                            TextButton(onClick = { removeTarget = book }) {
                                Text(stringResource(R.string.remove_book))
                            }
                        }
                    }
                }
            }
        }
    }

    assignTarget?.let { target ->
        AssignCollectionDialog(
            current = target.collectionName,
            onDismiss = { assignTarget = null },
            onSave = { newName ->
                scope.launch {
                    viewModel.setCollection(target, newName)
                    assignTarget = null
                }
            }
        )
    }

    renameTarget?.let { old ->
        RenameCollectionDialog(
            current = old,
            onDismiss = { renameTarget = null },
            onSave = { newName ->
                scope.launch {
                    val result = runCatching { viewModel.renameCollection(old, newName) }
                    renameTarget = null
                    if (result.isSuccess) {
                        if (selected == old) selected = newName.trim().ifBlank { old }
                    } else {
                        // Don't pretend the rename worked: tell the user.
                        Toast.makeText(
                            context,
                            result.exceptionOrNull()?.message ?: "Rename failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.remove_from_collection)) },
            text = { Text(name) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.deleteCollection(name)
                        deleteTarget = null
                        if (selected == name) selected = null
                    }
                }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    resetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(stringResource(R.string.start_over)) },
            // Mirrors the WPF restart prompt: progress resets to zero.
            text = { Text("پیشرفت صفر میشه و از فصل ۱ شروع می‌کنی.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.resetProgress(target)
                        resetTarget = null
                    }
                }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetTarget = null }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.remove_book)) },
            // Unlike WPF (which keeps the file), mobile removes the EPUB
            // file and the cover when unreferenced — the text says so.
            text = { Text("«${displayTitle(target, books)}» از کتابخانه حذف بشه؟\nفایل EPUB و جلد هم پاک می‌شن.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.removeBook(target)
                        removeTarget = null
                    }
                }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }
}

@Composable
fun RenameCollectionDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_collection)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.new_collection_name)) },
                    singleLine = true
                )
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isBlank()) {
                    error = "اسم مجموعه نمی‌تونه خالی باشه."
                } else {
                    onSave(text.trim())
                }
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun AssignCollectionDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collection)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
