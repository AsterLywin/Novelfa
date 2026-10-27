package com.novelfa.reader.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.novelfa.reader.R
import com.novelfa.reader.data.SUPPORTED_BOOK_MIME_TYPES
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.settings.CollectionException
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.data.isInProgress
import com.novelfa.reader.data.progressPercent
import kotlinx.coroutines.flow.first
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
    // Includes empty manually-created collections (upstream CollectionRegistry).
    val collections by viewModel.collectionNames.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var assignTarget by remember { mutableStateOf<BookEntity?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var resetTarget by remember { mutableStateOf<BookEntity?>(null) }
    var removeTarget by remember { mutableStateOf<BookEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val addedFmt = stringResource(R.string.import_summary_added)
    val dupFmt = stringResource(R.string.import_summary_duplicates)
    val failedFmt = stringResource(R.string.import_summary_failed)
    val restoredFmt = stringResource(R.string.import_summary_restored)
    val countFmt = stringResource(R.string.collection_count)

    // WPF parity: import books straight into a collection (CollectionAdd).
    var importTarget by remember { mutableStateOf<String?>(null) }
    // Import progress (same reason as LibraryScreen): a slow parse with
    // no feedback reads as a broken import.
    var isImporting by remember { mutableStateOf(false) }
    val pdfPasswordPrompt = rememberPdfPasswordPrompt()
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = importTarget
        importTarget = null
        if (uris.isNotEmpty() && target != null) {
            isImporting = true
            scope.launch {
                try {
                val summary = viewModel.importMultiple(
                    uris, passwordProvider = pdfPasswordPrompt.provider
                )
                if (summary.addedNames.isNotEmpty()) {
                    val all = viewModel.books.first()
                    for (key in summary.addedNames) {
                        all.firstOrNull { it.originalFileName?.lowercase() == key }
                            ?.let { viewModel.setCollection(it, target) }
                    }
                }
                val parts = mutableListOf<String>()
                if (summary.added > 0) parts.add(addedFmt.format(summary.added))
                if (summary.duplicates > 0) parts.add(dupFmt.format(summary.duplicates))
                if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
                if (summary.failed > 0) {
                    val base = failedFmt.format(summary.failed)
                    val sepC = if (java.util.Locale.getDefault().language.startsWith("en")) ", " else "، "
                    val shown = summary.failedDetails.take(3)
                        .joinToString(sepC) { "${it.name}: ${it.reason}" }
                    parts.add(if (summary.failedDetails.isEmpty()) base else "$base ($shown)")
                }
                if (parts.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        parts.joinToString(
                            if (java.util.Locale.getDefault().language.startsWith("en")) ", " else "، "
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                } finally {
                    isImporting = false
                }
            }
        }
    }

    if (isImporting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.import_in_progress))
                }
            }
        )
    }

    PdfPasswordDialog(pdfPasswordPrompt)

    // Top safe area: the header must not collide with the status bar.
    Column(modifier = Modifier.statusBarsPadding()) {
        if (onBack != null) {
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = onBack) {
                    AppIcon(
                        if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                            AppIconKind.ArrowForward
                        } else {
                            AppIconKind.ArrowBack
                        },
                        tint = LocalContentColor.current,
                        size = 16.dp
                    )
                }
                Text(
                    if (selected == null) stringResource(R.string.library_collections) else selected!!,
                    modifier = Modifier.align(Alignment.CenterVertically).weight(1f),
                    // Explicit color: outside any Surface/button there is
                    // no LocalContentColor provider here, so an uncolored
                    // Text falls back to black on the dark background.
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                // Upstream parity: manually create a collection (may stay empty).
                TextButton(onClick = { createOpen = true }) {
                    AppIcon(AppIconKind.Plus, tint = LocalContentColor.current, size = 14.dp, strokeWidth = 2f)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.new_collection))
                }
            }
        }
        if (selected == null) {
            if (collections.isEmpty()) {
                Text(
                    stringResource(R.string.collections_empty_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn {
                items(collections) { name ->
                    val count = books.count { it.collectionName == name }
                    Card(
                        onClick = { selected = name },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, modifier = Modifier.weight(1f))
                                // WPF CollectionRow.CountText parity: «N کتاب» / «خالی».
                                Text(
                                    if (count == 0) stringResource(R.string.collection_empty)
                                    else countFmt.format(count)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    importTarget = name
                                    importPicker.launch(
                                        SUPPORTED_BOOK_MIME_TYPES
                                    )
                                }) {
                                    Text(stringResource(R.string.add_book))
                                }
                                TextButton(onClick = { renameTarget = name }) {
                                    Text(stringResource(R.string.rename))
                                }
                                TextButton(onClick = { deleteTarget = name }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val name = selected!!
            val inCollection by viewModel.byCollection(name).collectAsState(initial = emptyList())
            // WPF CollectionWindow header parity: «N جلد · M بخش · K جلد تموم‌شده»
            // + average progress + «continue» opening the in-progress book.
            val doneCount = inCollection.count { isCompleted(it) }
            val chapterCount = inCollection.sumOf { it.totalChapters }
            val avgProgress = if (inCollection.isNotEmpty()) {
                inCollection.sumOf { progressPercent(it) } / inCollection.size / 100.0
            } else 0.0
            val toContinue = remember(inCollection) {
                inCollection.filter { isInProgress(it) }
                    .maxByOrNull { it.lastOpenedDate ?: "" }
                    ?: inCollection.firstOrNull { !isCompleted(it) }
                    ?: inCollection.firstOrNull()
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(
                        R.string.collection_stats,
                        inCollection.size, chapterCount, doneCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { avgProgress.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { toContinue?.let { onOpenBook(it.id) } },
                        enabled = toContinue != null
                    ) {
                        Text(stringResource(R.string.continue_reading))
                    }
                    TextButton(onClick = {
                        importTarget = name
                        importPicker.launch(SUPPORTED_BOOK_MIME_TYPES)
                    }) {
                        Text(stringResource(R.string.add_book))
                    }
                }
            }
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
                                AppIcon(
                                    AppIconKind.Heart,
                                    tint = MaterialTheme.colorScheme.primary,
                                    size = 17.dp,
                                    filled = book.isFavorite
                                )
                            }
                            TextButton(onClick = { resetTarget = book }) {
                                // Like the WPF restart prompt.
                                Text(stringResource(R.string.start_over_button))
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

    if (createOpen) {
        CreateCollectionDialog(
            onDismiss = { createOpen = false },
            onSave = { name ->
                scope.launch {
                    val result = runCatching { viewModel.createCollection(name) }
                    createOpen = false
                    if (!result.isSuccess) {
                        val msgRes = (result.exceptionOrNull() as? CollectionException)?.resId
                            ?: R.string.collection_create_failed
                        Toast.makeText(
                            context,
                            context.getString(msgRes),
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
                        val msgRes = (result.exceptionOrNull() as? CollectionException)?.resId
                            ?: R.string.collection_rename_failed
                        Toast.makeText(
                            context,
                            context.getString(msgRes),
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
            text = { Text(stringResource(R.string.reset_progress_message)) },
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
            text = {
                Text(
                    stringResource(
                        R.string.remove_book_confirm_message,
                        displayTitle(target, books)
                    )
                )
            },
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
    val blankErr = stringResource(R.string.collection_name_blank)
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
                    error = blankErr
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

/**
 * Upstream NameInputWindow parity: dialog for creating a collection,
 * with inline validation (won't close until the name is valid).
 */
@Composable
private fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val blankErr = stringResource(R.string.collection_name_blank)
    val tooLongErr = stringResource(R.string.collection_name_too_long)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_collection)) },
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
                    error = blankErr
                } else if (text.trim().length > 60) {
                    error = tooLongErr
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
