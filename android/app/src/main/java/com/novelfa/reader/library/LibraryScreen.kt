package com.novelfa.reader.library

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelfa.reader.R
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.data.ImportException
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.isCompleted
import com.novelfa.reader.data.isInProgress
import com.novelfa.reader.data.percentDisplay
import com.novelfa.reader.data.progressPercent
import com.novelfa.reader.settings.PinDialog
import com.novelfa.reader.settings.PinManager
import com.novelfa.reader.settings.PinMode
import com.novelfa.reader.settings.PinSession
import com.novelfa.reader.settings.RankHook
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.RankService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private val TABS = listOf(
    Tab.ALL to R.string.all_books,
    Tab.READING to R.string.currently_reading,
    Tab.COMPLETED to R.string.completed,
    Tab.RECENT to R.string.recently_opened,
    Tab.FAVORITES to R.string.favorites,
    Tab.PRIVATE to R.string.str_private
)

private fun greetingRes(): Int {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.good_morning
        in 12..16 -> R.string.good_afternoon
        in 17..19 -> R.string.good_evening
        else -> R.string.good_night
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel,
    onOpenCollections: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    // Task 9: tab + query survive rotation/process death (DB is NOT
    // involved here — this is pure UI state).
    var tab by rememberSaveable { mutableStateOf(Tab.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortMode.DEFAULT) }
    var gridView by rememberSaveable { mutableStateOf(true) }
    val books by viewModel.filtered(tab, query).collectAsState(initial = emptyList())
    val allBooks by viewModel.books.collectAsState(initial = emptyList())
    val visible = remember(allBooks) { allBooks.filter { !it.isPrivate } }
    val shown = remember(books, allBooks, sort) { sortBooks(books, allBooks, sort) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val missingFileMsg = stringResource(R.string.book_file_missing)
    // Books whose EPUB file is gone (e.g. Auto Backup restored the DB but not
    // files/books/): show a warning badge and explain instead of opening the
    // reader into an error.
    var missingIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(shown) {
        missingIds = withContext(Dispatchers.IO) {
            shown.filter { it.filePath.isBlank() || !File(it.filePath).isFile }
                .map { it.id }.toSet()
        }
    }
    val openBookSafe: (String) -> Unit = { id ->
        if (id in missingIds) {
            scope.launch { snackbar.showSnackbar(missingFileMsg) }
        } else {
            onOpenBook(id)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    viewModel.import(uri)
                } catch (e: ImportException) {
                    snackbar.showSnackbar(e.messageFa)
                }
            }
        }
    }
    val launchImport = { picker.launch(arrayOf("application/epub+zip", "application/epub")) }

    val pinManager = viewModel.pinGate as? PinManager
    var unlocked by remember { mutableStateOf(PinSession.unlocked) }
    var pinOpen by remember { mutableStateOf(false) }
    // Re-check the gate whenever the tab is opened (PIN may be set/removed
    // from Settings while the library stays composed).
    var gateTick by remember { mutableIntStateOf(0) }
    // Task 8: refresh reading rank on first composition and whenever the tab
    // changes (approximation of "resume"), plus private-tab session unlock.
    val context = LocalContext.current
    val prefs = remember(context) { PrefsStore(context) }
    val profileName by prefs.profileName.collectAsState(initial = "")
    var welcomeOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        gateTick++
        runCatching {
            RankHook.refreshRank(viewModel.dao, PrefsStoreRankPrefs(prefs))
        }
    }
    LaunchedEffect(profileName) {
        if (profileName.isBlank()) welcomeOpen = true
    }

    var menuFor by remember { mutableStateOf<BookEntity?>(null) }
    var assignFor by remember { mutableStateOf<BookEntity?>(null) }
    var resetFor by remember { mutableStateOf<BookEntity?>(null) }
    var removeFor by remember { mutableStateOf<BookEntity?>(null) }

    val finishedCount = remember(allBooks) { allBooks.count { isCompleted(it) } }
    val rankName = remember(finishedCount) {
        RankService.Ranks[RankService.rankIndexForStatic(finishedCount)].nameFa
    }
    val hero = remember(allBooks) {
        allBooks.filter { isInProgress(it) && !it.isPrivate }
            .maxByOrNull { it.lastOpenedDate ?: "" }
    }

    @Suppress("UNUSED_EXPRESSION")
    gateTick // re-read the gate below when Settings changes the PIN
    var pinSet by remember { mutableStateOf(false) }
    LaunchedEffect(gateTick) {
        pinSet = viewModel.pinGate.hasPin()
    }
    val privateLocked = tab == Tab.PRIVATE && pinSet && !unlocked

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenCollections) {
                        Text(stringResource(R.string.library_collections))
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.app_settings))
                    }
                    TextButton(onClick = { aboutOpen = true }) { Text("ⓘ") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { launchImport() }) {
                Text("+")
            }
        }
    ) { padding ->
        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                libraryHeaderSpan(
                    profileName = profileName,
                    rankName = rankName,
                    bookCount = visible.size,
                    onRename = { renameOpen = true },
                    total = visible.size,
                    reading = visible.count { isInProgress(it) },
                    done = finishedCount,
                    fav = visible.count { it.isFavorite },
                    hero = if (tab == Tab.ALL && query.isBlank()) hero else null,
                    allBooks = allBooks,
                    onOpenBook = openBookSafe,
                    sort = sort,
                    onSort = { sort = it },
                    gridView = true,
                    onView = { gridView = it },
                    tab = tab,
                    onTab = { tab = it },
                    query = query,
                    onQuery = { query = it }
                )
                if (privateLocked) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PrivateLockedGate(onUnlock = { pinOpen = true })
                    }
                } else if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLibrary(onImport = { launchImport() })
                    }
                } else {
                    if (tab == Tab.PRIVATE && pinSet) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(
                                onClick = {
                                    PinSession.unlocked = false
                                    unlocked = false
                                }
                            ) {
                                Text(stringResource(R.string.locked))
                            }
                        }
                    }
                    items(shown, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            title = displayTitle(book, allBooks),
                            onClick = { openBookSafe(book.id) },
                            onMenuClick = { menuFor = book },
                            missingFile = book.id in missingIds
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                libraryHeaderList(
                    profileName = profileName,
                    rankName = rankName,
                    bookCount = visible.size,
                    onRename = { renameOpen = true },
                    total = visible.size,
                    reading = visible.count { isInProgress(it) },
                    done = finishedCount,
                    fav = visible.count { it.isFavorite },
                    hero = if (tab == Tab.ALL && query.isBlank()) hero else null,
                    allBooks = allBooks,
                    onOpenBook = openBookSafe,
                    sort = sort,
                    onSort = { sort = it },
                    gridView = false,
                    onView = { gridView = it },
                    tab = tab,
                    onTab = { tab = it },
                    query = query,
                    onQuery = { query = it }
                )
                if (privateLocked) {
                    item { PrivateLockedGate(onUnlock = { pinOpen = true }) }
                } else if (shown.isEmpty()) {
                    item { EmptyLibrary(onImport = { launchImport() }) }
                } else {
                    if (tab == Tab.PRIVATE && pinSet) {
                        item {
                            TextButton(
                                onClick = {
                                    PinSession.unlocked = false
                                    unlocked = false
                                }
                            ) {
                                Text(stringResource(R.string.locked))
                            }
                        }
                    }
                    items(shown, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            title = displayTitle(book, allBooks),
                            onClick = { openBookSafe(book.id) },
                            onMenuClick = { menuFor = book },
                            missingFile = book.id in missingIds
                        )
                    }
                }
            }
        }
    }

    if (pinOpen && pinManager != null) {
        PinDialog(
            mode = PinMode.VERIFY,
            manager = pinManager,
            onSuccess = {
                PinSession.unlocked = true
                unlocked = true
                pinOpen = false
            },
            onDismiss = { pinOpen = false }
        )
    }

    if (welcomeOpen) {
        ProfileNameDialog(
            title = stringResource(R.string.what_s_your_name),
            hint = stringResource(R.string.before_we_start_choose_a_name_for_yourself),
            initial = "",
            onDismiss = { welcomeOpen = false },
            onSave = { name ->
                scope.launch {
                    prefs.setProfileName(name)
                    welcomeOpen = false
                }
            }
        )
    }

    if (renameOpen) {
        ProfileNameDialog(
            title = stringResource(R.string.change_name),
            hint = stringResource(R.string.before_we_start_choose_a_name_for_yourself),
            initial = profileName,
            onDismiss = { renameOpen = false },
            onSave = { name ->
                scope.launch {
                    prefs.setProfileName(name)
                    renameOpen = false
                }
            }
        )
    }

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }

    menuFor?.let { target ->
        BookMenuDialog(
            book = target,
            all = allBooks,
            onDismiss = { menuFor = null },
            onFavorite = {
                scope.launch { viewModel.toggleFavorite(target) }
                menuFor = null
            },
            onCollection = {
                assignFor = target
                menuFor = null
            },
            onPrivate = {
                scope.launch { viewModel.togglePrivate(target) }
                menuFor = null
            },
            onReset = {
                resetFor = target
                menuFor = null
            },
            onRemove = {
                removeFor = target
                menuFor = null
            }
        )
    }

    assignFor?.let { target ->
        AssignCollectionDialog(
            current = target.collectionName,
            onDismiss = { assignFor = null },
            onSave = { newName ->
                scope.launch {
                    viewModel.setCollection(target, newName)
                    assignFor = null
                }
            }
        )
    }

    resetFor?.let { target ->
        AlertDialog(
            onDismissRequest = { resetFor = null },
            title = { Text(stringResource(R.string.start_over)) },
            text = { Text(displayTitle(target, allBooks)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.resetProgress(target)
                        resetFor = null
                    }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { resetFor = null }) { Text(stringResource(R.string.no)) }
            }
        )
    }

    removeFor?.let { target ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text(stringResource(R.string.remove_book)) },
            text = { Text(displayTitle(target, allBooks)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.removeBook(target)
                        removeFor = null
                    }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { removeFor = null }) { Text(stringResource(R.string.no)) }
            }
        )
    }
}

private fun LazyGridScope.libraryHeaderSpan(
    profileName: String,
    rankName: String,
    bookCount: Int,
    onRename: () -> Unit,
    total: Int,
    reading: Int,
    done: Int,
    fav: Int,
    hero: BookEntity?,
    allBooks: List<BookEntity>,
    onOpenBook: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    gridView: Boolean,
    onView: (Boolean) -> Unit,
    tab: Tab,
    onTab: (Tab) -> Unit,
    query: String,
    onQuery: (String) -> Unit
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        ProfileHeader(
            name = profileName.ifBlank { "…" },
            rankName = rankName,
            bookCount = bookCount,
            onRename = onRename
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(stringResource(greetingRes()), style = MaterialTheme.typography.headlineSmall)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        StatsStrip(total = total, reading = reading, done = done, fav = fav, onPick = onTab)
    }
    if (hero != null) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeroCard(
                book = hero,
                title = displayTitle(hero, allBooks),
                onContinue = { openBookSafe(hero.id) }
            )
        }
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        SortViewRow(sort = sort, onSort = onSort, gridView = gridView, onView = onView)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        LibraryTabs(tab = tab, onTab = onTab)
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.search_books)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun LazyListScope.libraryHeaderList(
    profileName: String,
    rankName: String,
    bookCount: Int,
    onRename: () -> Unit,
    total: Int,
    reading: Int,
    done: Int,
    fav: Int,
    hero: BookEntity?,
    allBooks: List<BookEntity>,
    onOpenBook: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    gridView: Boolean,
    onView: (Boolean) -> Unit,
    tab: Tab,
    onTab: (Tab) -> Unit,
    query: String,
    onQuery: (String) -> Unit
) {
    item {
        ProfileHeader(
            name = profileName.ifBlank { "…" },
            rankName = rankName,
            bookCount = bookCount,
            onRename = onRename
        )
    }
    item {
        Text(stringResource(greetingRes()), style = MaterialTheme.typography.headlineSmall)
    }
    item { StatsStrip(total = total, reading = reading, done = done, fav = fav, onPick = onTab) }
    if (hero != null) {
        item {
            HeroCard(
                book = hero,
                title = displayTitle(hero, allBooks),
                onContinue = { openBookSafe(hero.id) }
            )
        }
    }
    item {
        SortViewRow(sort = sort, onSort = onSort, gridView = gridView, onView = onView)
    }
    item { LibraryTabs(tab = tab, onTab = onTab) }
    item {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.search_books)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LibraryTabs(tab: Tab, onTab: (Tab) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = TABS.indexOfFirst { it.first == tab },
        edgePadding = 0.dp
    ) {
        TABS.forEach { (t, label) ->
            Tab(
                selected = tab == t,
                onClick = { onTab(t) },
                text = { Text(stringResource(label)) }
            )
        }
    }
}

@Composable
private fun StatsStrip(
    total: Int,
    reading: Int,
    done: Int,
    fav: Int,
    onPick: (Tab) -> Unit
) {
    val cards = listOf(
        total to Tab.ALL,
        reading to Tab.READING,
        done to Tab.COMPLETED,
        fav to Tab.FAVORITES
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cards.forEach { (count, t) ->
            Card(
                onClick = { onPick(t) },
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$count", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** book_books uses WPF "a|b" plural forms (split at runtime, like Loc.FN). */
@Composable
private fun pluralBooks(count: Int): String {
    val raw = stringResource(R.string.book_books)
    val parts = raw.split("|")
    val form = if (count == 1) parts.first() else parts.last()
    return form.format(count)
}

@Composable
private fun ProfileHeader(
    name: String,
    rankName: String,
    bookCount: Int,
    onRename: () -> Unit
) {
    Card(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(rankName, style = MaterialTheme.typography.labelMedium)
                Text(
                    pluralBooks(bookCount),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    book: BookEntity,
    title: String,
    onContinue: () -> Unit
) {
    Card(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(modifier = Modifier.width(72.dp).aspectRatio(0.75f)) {
                AsyncImage(
                    model = book.coverPath,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(stringResource(R.string.continue_reading), style = MaterialTheme.typography.labelMedium)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${book.lastChapterIndex + 1} از ${book.totalChapters}",
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SortViewRow(
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    gridView: Boolean,
    onView: (Boolean) -> Unit
) {
    var sortOpen by remember { mutableStateOf(false) }
    val sortLabel = when (sort) {
        SortMode.DEFAULT -> R.string.sort_default
        SortMode.RECENT_ADDED -> R.string.recently_added
        SortMode.LAST_READ -> R.string.last_read
        SortMode.NAME_AZ -> R.string.name_a_to_z
        SortMode.MOST_PROGRESS -> R.string.most_progress
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { sortOpen = true }) { Text(stringResource(sortLabel)) }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_default)) },
                    onClick = { onSort(SortMode.DEFAULT); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recently_added)) },
                    onClick = { onSort(SortMode.RECENT_ADDED); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.last_read)) },
                    onClick = { onSort(SortMode.LAST_READ); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.name_a_to_z)) },
                    onClick = { onSort(SortMode.NAME_AZ); sortOpen = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.most_progress)) },
                    onClick = { onSort(SortMode.MOST_PROGRESS); sortOpen = false }
                )
            }
        }
        Row {
            TextButton(onClick = { onView(true) }, enabled = !gridView) { Text("▦") }
            TextButton(onClick = { onView(false) }, enabled = gridView) { Text("☰") }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.empty_library))
        Text(
            stringResource(R.string.empty_library_hint),
            style = MaterialTheme.typography.labelMedium
        )
        TextButton(onClick = onImport) { Text(stringResource(R.string.add_book)) }
    }
}

@Composable
private fun PrivateLockedGate(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.private_section))
            TextButton(onClick = onUnlock) {
                Text(stringResource(R.string.enter_your_pin))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCard(
    book: BookEntity,
    title: String,
    onClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    missingFile: Boolean = false
) {
    Card(onClick = onClick) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.displayMedium
                )
                AsyncImage(
                    model = book.coverPath,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize()
                )
                if (book.isFavorite) {
                    Text(
                        text = "★",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
                if (missingFile) {
                    Text(
                        text = "⚠",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    )
                }
                if (!book.collectionName.isNullOrBlank()) {
                    Text(
                        text = book.collectionName!!,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
                if (onMenuClick != null) {
                    TextButton(
                        onClick = onMenuClick,
                        modifier = Modifier.align(Alignment.TopStart)
                    ) { Text("⋯") }
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
                Text(
                    text = if (isCompleted(book)) {
                        stringResource(R.string.completed)
                    } else {
                        "${percentDisplay(book)}٪"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    title: String,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    missingFile: Boolean = false
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(44.dp, 60.dp)) {
                AsyncImage(
                    model = book.coverPath,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (isCompleted(book)) {
                        stringResource(R.string.completed)
                    } else {
                        "${percentDisplay(book)}٪"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            if (missingFile) Text("⚠")
            if (book.isFavorite) Text("★")
            TextButton(onClick = onMenuClick) { Text("⋯") }
        }
    }
}

@Composable
private fun BookMenuDialog(
    book: BookEntity,
    all: List<BookEntity>,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onCollection: () -> Unit,
    onPrivate: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                displayTitle(book, all),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                TextButton(onClick = onFavorite) {
                    Text(
                        if (book.isFavorite) stringResource(R.string.remove_from_favorites)
                        else stringResource(R.string.favorites_2)
                    )
                }
                TextButton(onClick = onCollection) {
                    Text(stringResource(R.string.add_to_collection))
                }
                TextButton(onClick = onPrivate) {
                    Text(
                        if (book.isPrivate) stringResource(R.string.make_public)
                        else stringResource(R.string.make_private)
                    )
                }
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.start_over))
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.remove_book))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ProfileNameDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = false },
                    singleLine = true,
                    isError = error,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = text.trim()
                if (clean.length < 2) {
                    error = true
                } else {
                    onSave(clean)
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column {
                Text(stringResource(R.string.about_body))
                if (version.isNotBlank()) {
                    Text(
                        stringResource(R.string.app_version, version),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}
