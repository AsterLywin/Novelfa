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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BookEntity
import kotlinx.coroutines.launch

/**
 * Collections basics ported from WPF CollectionWindow: distinct collection
 * names with counts, tap to open the filtered grid, long-press a card to
 * assign it to a collection, star toggle for favorites.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionsScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (String) -> Unit
) {
    val books by viewModel.books.collectAsState(initial = emptyList())
    val collections = remember(books) {
        books.mapNotNull { it.collectionName?.takeIf { n -> n.isNotBlank() } }
            .distinct()
            .sorted()
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var assignTarget by remember { mutableStateOf<BookEntity?>(null) }
    val scope = rememberCoroutineScope()

    Column {
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
                            Text(name)
                            Text("$count")
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
                        TextButton(
                            onClick = { scope.launch { viewModel.toggleFavorite(book) } }
                        ) {
                            Text(if (book.isFavorite) "★" else "☆")
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
