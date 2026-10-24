package com.novelfa.reader.library

import android.content.Intent
import android.util.Log
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.novelfa.reader.settings.PinThrottle
import com.novelfa.reader.settings.RankHook
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.theme.CharacterTheme
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.CelebrationTier
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.XpLevel
import com.novelfa.reader.theme.XpRank
import com.novelfa.reader.theme.RankService
import com.novelfa.reader.theme.RanksDialog
import com.novelfa.reader.theme.StarRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Calendar
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import com.novelfa.reader.theme.EmblemWatermark
import com.novelfa.reader.theme.LocalAppPaletteExtras
import com.novelfa.reader.theme.StitchDiagonal
import com.novelfa.reader.theme.StitchDiagonalLarge
import com.novelfa.reader.theme.StitchSeam
import com.novelfa.reader.theme.StitchStrip
import com.novelfa.reader.theme.ParticleLayer

private val TABS = listOf(
    Tab.ALL to R.string.all_books,
    Tab.READING to R.string.currently_reading,
    Tab.COMPLETED to R.string.completed,
    Tab.RECENT to R.string.recently_opened,
    Tab.FAVORITES to R.string.favorites,
    Tab.PRIVATE to R.string.str_private
)

/** Greeting + sub-line resources for the time of day (Windows GetPeriod ranges). */
private fun greetingRes(): Pair<Int, Int> {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.good_morning to R.string.greeting_sub_morning
        in 12..16 -> R.string.good_afternoon to R.string.greeting_sub_afternoon
        in 17..20 -> R.string.good_evening to R.string.greeting_sub_evening
        else -> R.string.good_night to R.string.greeting_sub_night
    }
}

/**
 * The time-of-day greeting block, Windows LibraryWindow parity
 * (txtGreeting/txtGreetingSub): the greeting at 26sp bold in the theme
 * text color, and below it the "… چی می‌خونی؟" line at 13sp muted with
 * a 4dp gap. Colors are explicit — as a bare grid/list item there is no
 * card supplying a content color, so an uncolored Text renders black
 * on the dark background.
 */
@Composable
private fun GreetingHeader() {
    val (greeting, sub) = greetingRes()
    Column {
        Text(
            stringResource(greeting),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            stringResource(sub),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel,
    onOpenCollections: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // EPUB opened from a file manager (ACTION_VIEW intent, WPF
    // file-association parity). Consumed once via onImportUriConsumed.
    pendingImportUri: Uri? = null,
    onImportUriConsumed: () -> Unit = {}
) {
    // Task 9: tab + query survive rotation/process death (DB is NOT
    // involved here — this is pure UI state).
    var tab by rememberSaveable { mutableStateOf(Tab.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    // Debounce the search: typing filters the full list on every keystroke;
    // wait 300ms after the last keystroke before filtering.
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }
    var sort by rememberSaveable { mutableStateOf(SortMode.DEFAULT) }
    // Secret theme codes (WPF TrackSecretKeys parity): typing BEAKO / EMT /
    // TEA unlocks the hidden themes; the code letters are scrubbed from the
    // search box like the desktop client does.
    var secretUnlocked by remember { mutableStateOf<CharacterTheme?>(null) }
    val secretCodes = remember {
        listOf("BEAKO" to "beatrice", "EMT" to "emilia", "TEA" to "echidna")
    }
    val context = LocalContext.current
    // PrefsStore must be built on the application context: the DataStore
    // delegate is process-wide, so an Activity context would leak the
    // Activity on recreation (e.g. in-app language change).
    val prefs = remember(context) { PrefsStore(context.applicationContext) }
    // Single rank-prefs bridge for the screen: it preloads on IO once and
    // serves reads from memory (no runBlocking on the UI thread).
    val rankPrefs = remember(prefs) { PrefsStoreRankPrefs(prefs) }
    fun handleQuery(newQuery: String) {
        query = newQuery
        val run = newQuery.takeLastWhile { it in 'A'..'Z' || it in 'a'..'z' }
            .takeLast(16).uppercase()
        for ((code, themeId) in secretCodes) {
            if (!run.endsWith(code)) continue
            val theme = CharacterThemes.find(themeId)
            val service = RankService(rankPrefs)
            if (theme != null && !service.isUnlocked(theme)) {
                service.secretUnlock(themeId)
                query = newQuery.dropLast(code.length)
                secretUnlocked = theme
            }
            break
        }
    }
    var gridView by rememberSaveable { mutableStateOf(true) }
    // Scroll states for the library grid/list (the import FAB keys its
    // translucency off their isScrollInProgress).
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    // Cold-start gate: a null here means "the database has not answered
    // yet" — NOT "the library is empty". Seeding these with emptyList()
    // painted the empty-library state for the first frames of every
    // launch and then popped the real books in. Content below renders
    // only once BOTH flows have really emitted (libraryReady); until
    // then the screen is just the (correctly themed) background.
    val booksState by remember(tab, debouncedQuery) { viewModel.filtered(tab, debouncedQuery) }
        .collectAsState(initial = null)
    val allBooksState by viewModel.books.collectAsState(initial = null)
    val libraryReady = booksState != null && allBooksState != null
    val books = booksState.orEmpty()
    val allBooks = allBooksState.orEmpty()
    val visible = remember(allBooks) { allBooks.filter { !it.isPrivate } }
    val shown = remember(books, allBooks, sort) { sortBooks(books, allBooks, sort) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val missingFileMsg = stringResource(R.string.book_file_missing)
    val addedFmt = stringResource(R.string.import_summary_added)
    val dupFmt = stringResource(R.string.import_summary_duplicates)
    val failedFmt = stringResource(R.string.import_summary_failed)
    val restoredFmt = stringResource(R.string.import_summary_restored)
    val avatarCopyErr = stringResource(R.string.couldn_t_copy_the_avatar)
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
    var restartAsk by remember { mutableStateOf<BookEntity?>(null) }
    val openBookSafe: (String) -> Unit = { id ->
        if (id in missingIds) {
            scope.launch { snackbar.showSnackbar(missingFileMsg) }
        } else {
            val book = allBooks.firstOrNull { it.id == id }
            // WPF parity: opening an already-started book asks «start over?»
            // (Yes = reset to chapter 1, No = continue, dismiss = cancel).
            if (book != null && isInProgress(book)) {
                restartAsk = book
            } else {
                onOpenBook(id)
            }
        }
    }

    /**
     * Shared import path for the SAF picker and the ACTION_VIEW file intent:
     * batch summary on success, diagnostic detail on a single-file failure.
     */
    // State for duplicate-overwrite confirmation dialog
    var pendingDuplicates by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingDuplicateNames by remember { mutableStateOf<List<String>>(emptyList()) }

    suspend fun importUris(uris: List<Uri>) {
        // List separator follows the UI language — never a hardcoded «، ».
        val sep = if (java.util.Locale.getDefault().language.startsWith("en")) ", " else "، "
        try {
            val summary = viewModel.importMultiple(uris)
            // If duplicates found, ask for confirmation instead of silently skipping
            if (summary.duplicates > 0 && summary.duplicateUris.isNotEmpty()) {
                pendingDuplicates = summary.duplicateUris
                pendingDuplicateNames = summary.duplicateNames
                // Still show the summary for added/failed
                val parts = mutableListOf<String>()
                if (summary.added > 0) parts.add(addedFmt.format(summary.added))
                if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
                if (summary.failed > 0) parts.add(failedFmt.format(summary.failed))
                if (parts.isNotEmpty()) snackbar.showSnackbar(parts.joinToString(sep))
                return
            }
            val parts = mutableListOf<String>()
            if (summary.added > 0) parts.add(addedFmt.format(summary.added))
            if (summary.duplicates > 0) parts.add(dupFmt.format(summary.duplicates))
            if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
            if (summary.failed > 0) parts.add(failedFmt.format(summary.failed))
            if (parts.isNotEmpty()) snackbar.showSnackbar(parts.joinToString(sep))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            // DIAGNOSTIC (temporary): surface the real cause in the UI so a
            // failing import can be root-caused from a screenshot alone.
            // Remove once the import failure is understood and fixed.
            val cause = e.cause
            val detail = (cause?.javaClass?.simpleName ?: "no-cause") +
                ": " + (cause?.message ?: e.message)
            snackbar.showSnackbar(e.messageFa + "\n" + detail)
        } catch (e: Exception) {
            // Anything else (Room errors, unexpected throws) must not crash
            // the app: show a generic message with the diagnostic detail.
            val detail = (e.javaClass.simpleName ?: "no-cause") + ": " + (e.message ?: "")
            snackbar.showSnackbar(
                context.getString(R.string.import_error_not_valid_epub) + "\n" + detail
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importUris(uris)
            }
        }
    }
    val launchImport = { picker.launch(arrayOf("application/epub+zip", "application/epub")) }

    // EPUB handed in from a file manager (ACTION_VIEW). Imported once, then
    // the URI is consumed so rotation doesn't re-import it. Consume AFTER the
    // import: consuming first changes the effect key and Compose cancels the
    // in-flight import.
    LaunchedEffect(pendingImportUri) {
        val uri = pendingImportUri ?: return@LaunchedEffect
        importUris(listOf(uri))
        onImportUriConsumed()
    }

    val pinManager = viewModel.pinGate as? PinManager
    var unlocked by remember { mutableStateOf(PinSession.unlocked) }
    var pinOpen by remember { mutableStateOf(false) }
    var forgotConfirm by remember { mutableStateOf(false) }
    // Re-check the gate whenever the tab is opened (PIN may be set/removed
    // from Settings while the library stays composed).
    var gateTick by remember { mutableIntStateOf(0) }
    // Task 8: refresh reading rank on first composition and whenever the tab
    // changes (approximation of "resume"), plus private-tab session unlock.
    // initial=null: the placeholder must be distinguishable from a real blank
    // value, otherwise the welcome dialog opens on every cold start before
    // DataStore delivers the saved name (and never closes afterwards).
    val profileName by prefs.profileName.collectAsState(initial = null)
    // UI language for hardcoded rank names (stringResource handles the rest
    // via locale; RankInfo carries its own fa/en names).
    val appLang by prefs.lang.collectAsState(initial = "fa")
    val isEnglishUi = appLang.startsWith("en")
    var welcomeOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    val guideShown by prefs.guideShown.collectAsState(initial = false)
    var renameOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    // XP celebration state: refreshXpRank runs on the HOME screen only
    // (never in the reader), and re-runs on resume (gateTick) so a
    // celebration earned mid-reading shows on return home, even if the
    // app was closed in between. It stays pending until dismissed.
    var xpRankUp by remember { mutableStateOf<RankHook.XpRankUp?>(null) }
    var xpLevel by remember { mutableStateOf<XpLevel?>(null) }
    var showRanks by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        gateTick++
        try {
            RankHook.refreshXpRank(viewModel.dao, rankPrefs, prefs.getFinishedBooksRestored())
                ?.let { xpRankUp = it }
            xpLevel = RankHook.xpLevelNow(viewModel.dao, rankPrefs, prefs.getFinishedBooksRestored())
        } catch (e: Exception) {
            // Was runCatching{} with no onFailure: a failing refresh silently
            // skipped the celebration with nothing in logcat. Log it so a
            // missing celebration is diagnosable instead of invisible.
            Log.w("NovelfaXp", "XP celebration refresh failed", e)
        }
    }
    // Re-check XP on resume (gateTick): returning from the reader does not
    // change `tab`, so the effect above would not re-run — and the
    // celebration earned mid-reading would wait for a tab switch. This
    // effect must NOT increment gateTick itself (that would loop).
    LaunchedEffect(gateTick) {
        try {
            RankHook.refreshXpRank(viewModel.dao, rankPrefs, prefs.getFinishedBooksRestored())
                ?.let { xpRankUp = it }
            xpLevel = RankHook.xpLevelNow(viewModel.dao, rankPrefs, prefs.getFinishedBooksRestored())
        } catch (e: Exception) {
            // See the LaunchedEffect(tab) block above: never swallow this
            // silently, or a missing celebration leaves no trace.
            Log.w("NovelfaXp", "XP celebration refresh failed", e)
        }
    }
    // Also re-check on resume: the PIN can be set/removed in Settings, and
    // the back navigation doesn't change `tab`, so the gate above wouldn't
    // re-run — leaving the private tab permanently locked after PIN removal.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) gateTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(profileName, guideShown) {
        val name = profileName ?: return@LaunchedEffect // value not loaded yet
        if (name.isBlank()) {
            // First run: the guide goes before the name prompt and is
            // shown once (guideShown), while the name prompt reappears
            // every restart until a name is set (existing behavior).
            // Existing users have a name, so this never fires for them
            // even though their guideShown starts false.
            if (!guideShown) {
                guideOpen = true
                welcomeOpen = false
            } else {
                welcomeOpen = true
            }
        }
    }

    // Selection mode (long-press a book): empty = off. Tapping toggles;
    // when the last book is deselected the mode exits by itself.
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    // Avatar: blank path = default Novelfa icon (like the desktop client).
    val avatarPath by prefs.avatarPath.collectAsState(initial = "")
    var avatarOpen by remember { mutableStateOf(false) }
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
                        val dest = File(dir, "avatar.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IOException("cannot open image")
                        dest.absolutePath
                    }
                }
                result.onSuccess { path -> prefs.setAvatarPath(path) }
                    .onFailure { snackbar.showSnackbar(avatarCopyErr) }
                avatarOpen = false
            }
        }
    }

    var menuFor by remember { mutableStateOf<BookEntity?>(null) }
    var assignFor by remember { mutableStateOf<BookEntity?>(null) }
    var resetFor by remember { mutableStateOf<BookEntity?>(null) }
    var removeFor by remember { mutableStateOf<BookEntity?>(null) }

    val finishedCount = remember(allBooks) { allBooks.count { isCompleted(it) } }
    val rankTitle = remember(xpLevel, isEnglishUi) {
        (xpLevel ?: XpRank.levelFor(0)).title.displayName(isEnglishUi)
    }
    val rankStarFill = remember(xpLevel) {
        (xpLevel ?: XpRank.levelFor(0)).starFill
    }
    // Precompute display titles once per book-list emission (O(n), not O(n²)).
    val titleMap = remember(allBooks) { displayTitleMap(allBooks) }
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

    // Character-theme particle layer (WPF parity): drawn behind the library
    // content when the active character theme defines particles and the user
    // enabled them in the theme picker.
    val charThemeId by prefs.charTheme.collectAsState(initial = "")
    val particlesOn by prefs.themeParticles.collectAsState(initial = false)
    val particleType = remember(charThemeId) {
        CharacterThemes.find(charThemeId)?.particle.orEmpty()
    }
    val particleTheme = remember(charThemeId) { CharacterThemes.find(charThemeId) }
    val appThemeKey by prefs.theme.collectAsState(initial = "dark")

    Box(modifier = Modifier.fillMaxSize()) {
        ParticleLayer(
            type = particleType,
            enabled = particlesOn,
            theme = particleTheme,
            light = appThemeKey == "light"
        )

        // Emblem watermarks behind the content (Windows ThemeArt parity:
        // 230dp centered at 7%, 420dp bleeding off the bottom-end at 6%).
        val themeExtras = LocalAppPaletteExtras.current
        if (themeExtras.hasCharacterTheme && themeExtras.emblemPath != null) {
            EmblemWatermark(
                path = themeExtras.emblemPath!!,
                grad1 = themeExtras.accentGrad1,
                grad2 = themeExtras.accentGrad2,
                alpha = 0.07f,
                modifier = Modifier.size(230.dp).align(Alignment.Center)
            )
            EmblemWatermark(
                path = themeExtras.emblemPath!!,
                grad1 = themeExtras.accentGrad1,
                grad2 = themeExtras.accentGrad2,
                alpha = 0.06f,
                modifier = Modifier
                    .size(420.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 60.dp, y = 80.dp)
            )
        }

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // The Novelfa logo replaces the text title; white artwork on
                // dark themes, black artwork on light themes. The PNGs live in
                // drawable-nodpi/ (copied by Aster; binaries can't be pushed
                // via the GitHub connector).
                title = {
                    Image(
                        painter = painterResource(
                            if (appThemeKey == "light") R.drawable.novelfa_logo_on_light
                            else R.drawable.novelfa_logo_on_dark
                        ),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.height(36.dp),
                        contentScale = ContentScale.Fit
                    )
                },
                // Match the Windows version: header uses the background color
                // (Bg), not the card/surface color.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    TextButton(onClick = onOpenCollections) {
                        Text(stringResource(R.string.library_collections))
                    }
                    IconButton(onClick = { aboutOpen = true }) {
                        AppIcon(AppIconKind.Info, tint = MaterialTheme.colorScheme.onSurface, size = 19.dp)
                    }
                    IconButton(onClick = onOpenSettings) {
                        AppIcon(AppIconKind.Gear, tint = MaterialTheme.colorScheme.onSurface, size = 19.dp)
                    }
                }
            )
        },
        floatingActionButton = {
            // Translucent ONLY while the list is actively scrolling;
            // at rest the button is fully opaque at ANY scroll position
            // (it used to key off the scroll position instead, which
            // left it faded on a stationary scrolled page).
            val scrolling = if (gridView) {
                gridState.isScrollInProgress
            } else {
                listState.isScrollInProgress
            }
            val fabAlpha by animateFloatAsState(
                targetValue = if (scrolling) 0.55f else 1f,
                animationSpec = tween(durationMillis = 150),
                label = "fabAlpha"
            )
            if (themeExtras.hasCharacterTheme) {
                // Windows parity: the primary action wears the theme's
                // accent gradient (AccentGradient brush).
                Box(
                    modifier = Modifier
                        .alpha(fabAlpha)
                        .size(56.dp)
                        .shadow(6.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    themeExtras.accentGrad1,
                                    themeExtras.accentGrad2
                                )
                            )
                        )
                        .clickable { launchImport() },
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        AppIconKind.Plus,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = 26.dp,
                        strokeWidth = 2f
                    )
                }
            } else {
                FloatingActionButton(
                    onClick = { launchImport() },
                    modifier = Modifier.alpha(fabAlpha)
                ) {
                    AppIcon(
                        AppIconKind.Plus,
                        tint = LocalContentColor.current,
                        size = 24.dp,
                        strokeWidth = 2f
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    onDelete = { deleteConfirm = true },
                    onCancel = { selection = emptySet() }
                )
            }
            if (!libraryReady) {
                // Books still loading: themed background only — never a
                // fake empty library, never a half-populated header.
                Box(Modifier.fillMaxSize())
            } else if (gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                ) {
                libraryHeaderSpan(
                    profileName = profileName ?: "",
                    rankTitle = rankTitle,
                    rankStarFill = rankStarFill,
                    bookCount = visible.size,
                    avatarPath = avatarPath,
                    onAvatarClick = { avatarOpen = true },
                    onRename = { renameOpen = true },
                    onRankClick = { showRanks = true },
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
                    onQuery = ::handleQuery
                )
                if (privateLocked) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PrivateLockedGate(onUnlock = { pinOpen = true })
                    }
                } else if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (query.isNotBlank() && allBooks.isNotEmpty()) {
                            NoSearchResults()
                        } else {
                            EmptyLibrary(tab = tab, onImport = { launchImport() })
                        }
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
                            title = titleMap[book.id] ?: book.title,
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = if (book.id in selection) {
                                        selection - book.id
                                    } else {
                                        selection + book.id
                                    }
                                } else {
                                    openBookSafe(book.id)
                                }
                            },
                            onLongClick = {
                                selection = if (book.id in selection) {
                                    selection - book.id
                                } else {
                                    selection + book.id
                                }
                            },
                            onMenuClick = { menuFor = book },
                            missingFile = book.id in missingIds,
                            selected = book.id in selection,
                            // Bite mark: completed books only, tsugi theme only.
                            showBite = charThemeId == "tsugi" && isCompleted(book),
                            showStitch = charThemeId == "tsugi",
                        )
                    }
                }
            }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                libraryHeaderList(
                    profileName = profileName ?: "",
                    rankTitle = rankTitle,
                    rankStarFill = rankStarFill,
                    bookCount = visible.size,
                    avatarPath = avatarPath,
                    onAvatarClick = { avatarOpen = true },
                    onRename = { renameOpen = true },
                    onRankClick = { showRanks = true },
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
                    onQuery = ::handleQuery
                )
                if (privateLocked) {
                    item { PrivateLockedGate(onUnlock = { pinOpen = true }) }
                } else if (shown.isEmpty()) {
                    item {
                        if (query.isNotBlank() && allBooks.isNotEmpty()) {
                            NoSearchResults()
                        } else {
                            EmptyLibrary(tab = tab, onImport = { launchImport() })
                        }
                    }
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
                            title = titleMap[book.id] ?: book.title,
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = if (book.id in selection) {
                                        selection - book.id
                                    } else {
                                        selection + book.id
                                    }
                                } else {
                                    openBookSafe(book.id)
                                }
                            },
                            onLongClick = {
                                selection = if (book.id in selection) {
                                    selection - book.id
                                } else {
                                    selection + book.id
                                }
                            },
                            onMenuClick = { menuFor = book },
                            missingFile = book.id in missingIds,
                            selected = book.id in selection
                        )
                    }
                }
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
            onDismiss = { pinOpen = false },
            onForgotPin = {
                pinOpen = false
                forgotConfirm = true
            }
        )
    }

    // Forgot-PIN recovery: the user is locked out, so the only safe way
    // back is destroying the protected content, never bypassing the lock.
    // Confirm first (irreversible), then delete all private books, clear
    // the PIN and the throttle, and unlock the (now empty) private tab.
    if (forgotConfirm && pinManager != null) {
        val forgotDoneMsg = stringResource(R.string.forgot_pin_done)
        AlertDialog(
            onDismissRequest = { forgotConfirm = false },
            title = { Text(stringResource(R.string.forgot_pin)) },
            text = { Text(stringResource(R.string.forgot_pin_message)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.deleteAllPrivateBooks()
                        pinManager.clearPin()
                        PinThrottle.reset()
                        PinSession.unlocked = true
                        unlocked = true
                        forgotConfirm = false
                        snackbar.showSnackbar(forgotDoneMsg)
                    }
                }) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { forgotConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (guideOpen) {
        // First run (blank name): finishing marks the guide shown and
        // opens the name prompt; dismissing (back/outside) opens the
        // name prompt without marking it, so it returns next restart.
        // Re-opened from About (name set): both paths just close.
        FirstRunGuide(
            onFinish = {
                guideOpen = false
                if ((profileName ?: "").isBlank()) {
                    scope.launch {
                        prefs.setGuideShown(true)
                        welcomeOpen = true
                    }
                }
            },
            onDismiss = {
                guideOpen = false
                if ((profileName ?: "").isBlank()) welcomeOpen = true
            }
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
            initial = profileName ?: "",
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
        AboutDialog(
            onDismiss = { aboutOpen = false },
            onShowGuide = {
                aboutOpen = false
                guideOpen = true
            }
        )
    }

    if (showRanks) {
        RanksDialog(
            totalXp = xpLevel?.xp ?: 0,
            isEnglishUi = isEnglishUi,
            onDismiss = { showRanks = false }
        )
    }

    // XP celebration (home screen only): tiered by the highest new step —
    // star (small), title (medium), eternity mark (large), 10th mark
    // (largest, special). Dismissing/confirming always acknowledges the
    // celebration, so it never loops; activating a theme is optional.
    xpRankUp?.let { up ->
        val tierTitleRes = when (up.tier) {
            CelebrationTier.STAR -> R.string.xp_celebration_star_title
            CelebrationTier.TITLE -> R.string.xp_celebration_title_title
            CelebrationTier.ETERNITY -> R.string.xp_celebration_mark_title
            CelebrationTier.ETERNITY_FINAL -> R.string.xp_celebration_final_title
        }
        val best = up.bestTheme
        fun dismissXpUp() {
            scope.launch {
                RankHook.acknowledgeXpCelebration(rankPrefs, up.toXp)
                xpRankUp = null
            }
        }
        AlertDialog(
            onDismissRequest = { dismissXpUp() },
            title = { Text(stringResource(tierTitleRes)) },
            text = {
                Column {
                    Text(
                        if (best != null) {
                            val names = up.newThemes.joinToString(if (isEnglishUi) ", " else "، ") {
                                it.displayName(isEnglishUi)
                            }
                            stringResource(
                                R.string.xp_celebration_with_theme,
                                up.toLevel.title.displayName(isEnglishUi),
                                names,
                                best.displayName(isEnglishUi)
                            )
                        } else {
                            stringResource(
                                R.string.xp_celebration_plain,
                                up.toLevel.title.displayName(isEnglishUi),
                                up.toLevel.xpToNext
                            )
                        }
                    )
                    // Drawn stars under the sentence (fractional fill,
                    // like everywhere else) instead of glyph stars
                    // embedded in the message text.
                    Spacer(modifier = Modifier.height(10.dp))
                    StarRow(fill = up.toLevel.starFill, starSize = 20.dp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (best != null) prefs.setCharTheme(best.id)
                        RankHook.acknowledgeXpCelebration(rankPrefs, up.toXp)
                        xpRankUp = null
                    }
                }) {
                    Text(
                        if (best != null) stringResource(R.string.yes)
                        else stringResource(R.string.xp_celebration_ok)
                    )
                }
            },
            dismissButton = {
                if (best != null) {
                    TextButton(onClick = { dismissXpUp() }) { Text(stringResource(R.string.no)) }
                }
            }
        )
    }

    // Secret-code unlock celebration (WPF SecretTyped parity).
    secretUnlocked?.let { theme ->
        AlertDialog(
            onDismissRequest = { secretUnlocked = null },
            title = { Text(stringResource(R.string.new_theme_unlocked)) },
            text = { Text(stringResource(R.string.secret_theme_message, theme.displayName(isEnglishUi))) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        prefs.setCharTheme(theme.id)
                        secretUnlocked = null
                    }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { secretUnlocked = null }) { Text(stringResource(R.string.no)) }
            }
        )
    }

    // «Start over?» prompt (WPF AskRestart parity): shown when opening an
    // already-started book. Yes = reset to chapter 1, No = continue,
    // dismiss = cancel.
    restartAsk?.let { book ->
        AlertDialog(
            onDismissRequest = { restartAsk = null },
            title = { Text(stringResource(R.string.start_over)) },
            text = { Text(stringResource(R.string.start_over_prompt, displayTitle(book, allBooks))) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.resetProgress(book)
                        restartAsk = null
                        onOpenBook(book.id)
                    }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = book.id
                    restartAsk = null
                    onOpenBook(id)
                }) { Text(stringResource(R.string.no)) }
            }
        )
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

    if (deleteConfirm) {
        val targets = selection.mapNotNull { id -> allBooks.firstOrNull { it.id == id } }
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(R.string.delete_n_books, selection.size)) },
            text = {
                Text(
                    targets.take(5)
                        .joinToString("\n") { displayTitle(it, allBooks) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ids = targets.map { it.id }
                        selection = emptySet()
                        deleteConfirm = false
                        withContext(Dispatchers.IO) {
                            ids.forEach { id ->
                                allBooks.firstOrNull { it.id == id }
                                    ?.let { viewModel.removeBook(it) }
                            }
                        }
                    }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.no)) }
            }
        )
    }

    // Duplicate import: the overwrite confirmation that importUris() arms.
    // (This render was missing, so no dialog ever appeared.)
    if (pendingDuplicates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                pendingDuplicates = emptyList()
                pendingDuplicateNames = emptyList()
            },
            title = { Text(stringResource(R.string.duplicate_file_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.duplicate_file_message))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        pendingDuplicateNames.take(5).joinToString("\n"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pendingDuplicateNames.size > 5) {
                        Text(
                            "… +${pendingDuplicateNames.size - 5}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uris = pendingDuplicates
                    pendingDuplicates = emptyList()
                    pendingDuplicateNames = emptyList()
                    scope.launch {
                        val summary = viewModel.overwriteDuplicates(uris)
                        val parts = mutableListOf<String>()
                        if (summary.added > 0) parts.add(addedFmt.format(summary.added))
                        if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
                        if (summary.failed > 0) parts.add(failedFmt.format(summary.failed))
                        if (parts.isNotEmpty()) {
                            snackbar.showSnackbar(parts.joinToString(if (isEnglishUi) ", " else "، "))
                        }
                    }
                }) { Text(stringResource(R.string.yes_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDuplicates = emptyList()
                    pendingDuplicateNames = emptyList()
                }) { Text(stringResource(R.string.no)) }
            }
        )
    }

    if (avatarOpen) {
        AlertDialog(
            onDismissRequest = { avatarOpen = false },
            title = { Text(stringResource(R.string.choose_avatar)) },            text = {
                Column {
                    TextButton(onClick = {
                        scope.launch {
                            prefs.setAvatarPath("")
                            avatarOpen = false
                        }
                    }) { Text(stringResource(R.string.default_avatar)) }
                    TextButton(onClick = { galleryPicker.launch("image/*") }) {
                        Text(stringResource(R.string.pick_from_gallery))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { avatarOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun LazyGridScope.libraryHeaderSpan(
    profileName: String,
    rankTitle: String,
    rankStarFill: Float,
    bookCount: Int,
    avatarPath: String,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
    onRankClick: () -> Unit,
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
            rankTitle = rankTitle,
            rankStarFill = rankStarFill,
            bookCount = bookCount,
            avatarPath = avatarPath,
            onAvatarClick = onAvatarClick,
            onRename = onRename,
            onRankClick = onRankClick
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        GreetingHeader()
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        StatsStrip(total = total, reading = reading, done = done, fav = fav, onPick = onTab)
    }
    if (hero != null) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeroCard(
                book = hero,
                title = displayTitle(hero, allBooks),
                onContinue = { onOpenBook(hero.id) }
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
    rankTitle: String,
    rankStarFill: Float,
    bookCount: Int,
    avatarPath: String,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
    onRankClick: () -> Unit,
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
            rankTitle = rankTitle,
            rankStarFill = rankStarFill,
            bookCount = bookCount,
            avatarPath = avatarPath,
            onAvatarClick = onAvatarClick,
            onRename = onRename,
            onRankClick = onRankClick
        )
    }
    item {
        GreetingHeader()
    }
    item { StatsStrip(total = total, reading = reading, done = done, fav = fav, onPick = onTab) }
    if (hero != null) {
        item {
            HeroCard(
                book = hero,
                title = displayTitle(hero, allBooks),
                onContinue = { onOpenBook(hero.id) }
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
        edgePadding = 0.dp,
        // Match the Windows version: tabs use the background color (Bg),
        // not the card/surface color.
        containerColor = MaterialTheme.colorScheme.background
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
    // Like the WPF stats strip: each card is one unit (count + label) and
    // tapping it selects the corresponding tab.
    val extras = LocalAppPaletteExtras.current
    val cards = listOf(
        Triple(total, Tab.ALL, R.string.all_books),
        Triple(reading, Tab.READING, R.string.currently_reading),
        Triple(done, Tab.COMPLETED, R.string.completed),
        Triple(fav, Tab.FAVORITES, R.string.favorites)
    )
    // Equal-height cards: in English "Currently reading" wraps to two
    // lines and used to make its card taller than its siblings (in
    // Persian the label fits one line, so it never showed). The row
    // takes the tallest card's height and every card stretches to it,
    // with its content vertically centered.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cards.forEach { (count, t, labelRes) ->
            Card(
                onClick = { onPick(t) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("$count", style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    // Tsugi stitch in the top-end corner of each stat card
                    // (Windows ThemeStitch parity).
                    if (extras.charThemeId == "tsugi") {
                        StitchDiagonal(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 6.dp)
                                .size(30.dp)
                        )
                    }
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
private fun SelectionBar(
    count: Int,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.n_selected, count),
                style = MaterialTheme.typography.titleSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    TrashCanIcon(
                        // Bar content color, not destructive red: fixed
                        // vivid red on the orange primaryContainer had
                        // almost no contrast and read as a muddy smudge.
                        // The trash shape + the delete-confirm dialog
                        // carry the destructive meaning; the color just
                        // needs to match the bar (and adapt to themes).
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // Cancel/close: bar content color, like the trash —
                // the old fixed yellow washed out on the orange bar.
                IconButton(onClick = onCancel) {
                    AppIcon(
                        AppIconKind.Close,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        size = 17.dp
                    )
                }
            }
        }
    }
}

/**
 * Vector trash-can icon (replaces the 🗑️ emoji): lid + handle + tapered
 * body outline, drawn with Canvas so it follows the theme instead of the
 * system emoji font.
 */@Composable
private fun TrashCanIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Slightly bolder than the old 0.09: at 22dp the thin outline
        // washed out against the orange bar.
        val stroke = w * 0.11f
        // Handle on the lid.
        drawLine(
            color = color,
            start = Offset(w * 0.38f, h * 0.06f),
            end = Offset(w * 0.62f, h * 0.06f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // Lid.
        drawLine(
            color = color,
            start = Offset(w * 0.12f, h * 0.20f),
            end = Offset(w * 0.88f, h * 0.20f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // Tapered body outline.
        drawPath(
            path = Path().apply {
                moveTo(w * 0.24f, h * 0.32f)
                lineTo(w * 0.76f, h * 0.32f)
                lineTo(w * 0.67f, h * 0.94f)
                lineTo(w * 0.33f, h * 0.94f)
                close()
            },
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Two vertical ribs inside the body.
        drawLine(
            color = color,
            start = Offset(w * 0.43f, h * 0.44f),
            end = Offset(w * 0.41f, h * 0.82f),
            strokeWidth = stroke * 0.75f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.57f, h * 0.44f),
            end = Offset(w * 0.59f, h * 0.82f),
            strokeWidth = stroke * 0.75f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Bite taken out of a completed book's cover (upstream ThemeBiteVisibility
 * parity): three overlapping background-colored circles chewing into the
 * top-end corner. Only shown with the «سوبارو · شکم‌پرستی» theme.
 */
@Composable
private fun BiteOverlay(modifier: Modifier = Modifier) {
    // Windows fills the bite with the window background (BgDark), so the
    // cover looks bitten through to what's behind the card.
    val bg = MaterialTheme.colorScheme.background
    val crumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Anchor the bite on the top-end corner: circles centered just
        // outside the corner so their arcs cut into the cover.
        drawCircle(bg, radius = w * 0.34f, center = Offset(w * 1.02f, -h * 0.04f))
        drawCircle(bg, radius = w * 0.26f, center = Offset(w * 0.78f, -h * 0.10f))
        drawCircle(bg, radius = w * 0.22f, center = Offset(w * 1.06f, h * 0.22f))
        // Crumbs scattered just inside the bite (Windows parity).
        listOf(
            Triple(0.58f, 0.80f, 0.055f),
            Triple(0.74f, 0.93f, 0.04f),
            Triple(0.44f, 0.95f, 0.045f)
        ).forEach { (fx, fy, fr) ->
            val cx = w * fx
            val cy = h * fy
            val r = w * fr
            val tri = Path().apply {
                moveTo(cx - r, cy + r * 0.6f)
                lineTo(cx + r * 0.4f, cy - r)
                lineTo(cx + r, cy + r * 0.8f)
                close()
            }
            drawPath(tri, crumb)
        }
    }
}

@Composable
private fun ProfileHeader(
    name: String,
    rankTitle: String,
    rankStarFill: Float,
    bookCount: Int,
    avatarPath: String,
    onAvatarClick: () -> Unit,
    onRename: () -> Unit,
    onRankClick: () -> Unit
) {
    Card(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar (like the desktop client): the Novelfa icon by default,
            // a user-picked image when set. Tapping it opens the picker.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                val avatarFile = avatarPath.takeIf { it.isNotBlank() }?.let { File(it) }
                if (avatarFile != null && avatarFile.isFile) {
                    AsyncImage(
                        model = avatarFile,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                // Rank badge with a colored outline, like the desktop client.
                // Tapping it opens the full ranks overview (same dialog as
                // the settings Progress card); the rest of the header still
                // opens rename, since this inner clickable wins on the badge.
                // Title + drawn stars (StarRow): the old glyph stars are
                // gone — fill is fractional, so progress reads precisely.
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clickable(onClick = onRankClick)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rankTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        StarRow(fill = rankStarFill, starSize = 13.dp)
                    }
                }
                Text(
                    pluralBooks(bookCount),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
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
    val extras = LocalAppPaletteExtras.current
    val scheme = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Card(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        // Windows parity: 1dp border + hero gradient background
        border = BorderStroke(1.dp, scheme.outline),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(scheme.secondary, scheme.tertiary)
                    )
                )
                // Windows accent glow: a radial wash of the accent color
                // at 32% fading out from the cover-side top corner.
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = 0.32f),
                                Color.Transparent
                            ),
                            center = Offset(
                                if (rtl) size.width * 0.88f else size.width * 0.12f,
                                size.height * 0.1f
                            ),
                            radius = size.width * 0.75f
                        )
                    )
                }
        ) {
        // Emblem watermark inside the hero (Windows: 190dp at 14%).
        if (extras.hasCharacterTheme && extras.emblemPath != null) {
            EmblemWatermark(
                path = extras.emblemPath!!,
                grad1 = extras.accentGrad1,
                grad2 = extras.accentGrad2,
                alpha = 0.14f,
                // matchParentSize: decoration only — it must not drive the
                // hero's height (a fixed 190dp here stretched the card and
                // left a void under the content).
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.CenterEnd
            )
        }
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The Windows "tilted book": an accent-gradient plate rotated
            // -10° behind the cover; the cover itself rotated +4°, with
            // 12dp rounded corners, crop fill and a soft shadow. (It used
            // at the card's original compact footprint.)
            val plateColors = if (extras.accentGrad1 == Color.Transparent) {
                listOf(scheme.primary, scheme.secondary)
            } else {
                listOf(extras.accentGrad1, extras.accentGrad2)
            }
            Box(
                modifier = Modifier.width(92.dp).height(106.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 96.dp)
                        .rotate(-10f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            Brush.linearGradient(
                                colors = plateColors.map { it.copy(alpha = 0.85f) }
                            )
                        )
                )
                AsyncImage(
                    model = book.coverPath,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 72.dp, height = 96.dp)
                        .shadow(10.dp, RoundedCornerShape(9.dp))
                        .rotate(4f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(scheme.surfaceVariant)
                )
            }
            // Tsugi torn seam between the cover and the text (Windows hero).
            if (extras.charThemeId == "tsugi") {
                StitchSeam(
                    textColor = scheme.onSurface,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .width(24.dp)
                        .height(72.dp)
                        .align(Alignment.CenterVertically)
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                // Windows text styles, with explicit colors (an uncolored
                // Text here renders in the default content color, which
                // read as near-black on the hero gradient).
                Text(
                    stringResource(R.string.continue_reading),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary
                )
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, bottom = 2.dp)
                )
                Text(
                    stringResource(
                        R.string.chapter_progress,
                        book.lastChapterIndex + 1,
                        book.totalChapters
                    ),
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.progress),
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.percent_value, percentDisplay(book)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface
                    )
                }
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = scheme.primary,
                    trackColor = scheme.outline
                )
                // Windows LightButton: in light themes it is accent with
                // on-accent text; in dark themes the text color is the
                // background and the page background is the foreground.
                val lightScheme = scheme.background.luminance() > 0.5f
                val btnFg = if (lightScheme) scheme.onPrimary else scheme.background
                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (lightScheme) scheme.primary else scheme.onSurface,
                        contentColor = btnFg
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.continue_keep_reading),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Canvas(Modifier.size(12.dp)) {
                        // IcoArrowForward, mirrored with the layout direction.
                        val sc = size.width / 16f
                        val path = Path()
                        if (rtl) {
                            path.moveTo(13f * sc, 8f * sc); path.lineTo(3f * sc, 8f * sc)
                            path.moveTo(7f * sc, 4f * sc); path.lineTo(3f * sc, 8f * sc)
                            path.lineTo(7f * sc, 12f * sc)
                        } else {
                            path.moveTo(3f * sc, 8f * sc); path.lineTo(13f * sc, 8f * sc)
                            path.moveTo(9f * sc, 4f * sc); path.lineTo(13f * sc, 8f * sc)
                            path.lineTo(9f * sc, 12f * sc)
                        }
                        drawPath(
                            path,
                            color = btnFg,
                            style = Stroke(
                                width = 1.6.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }
        }
        // Tsugi stitch in the hero's top-start corner (Windows: 64dp).
        if (extras.charThemeId == "tsugi") {
            StitchDiagonalLarge(
                color = scheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(64.dp)
            )
        }
        }  // Close gradient Box
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
        // Windows-parity view toggle: a segmented pill (card bg, 1dp
        // border, 10dp radius, 2dp padding) holding two 34x30 buttons;
        // the active view gets the AccentSoft background + accent icon,
        // the inactive one stays muted. (It used to be two TextButtons
        // with font glyphs "▦"/"☰" and enabled-as-selection, which
        // looked nothing like the desktop app.)
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewToggleButton(selected = gridView, onClick = { onView(true) }, grid = true)
            ViewToggleButton(selected = !gridView, onClick = { onView(false) }, grid = false)
        }
    }
}

/** One half of the view toggle; [grid] picks the Windows IcoGrid/IcoList path. */
@Composable
private fun ViewToggleButton(selected: Boolean, onClick: () -> Unit, grid: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 30.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(16.dp)) {
            val s = size.width / 16f
            val path = Path()
            if (grid) {
                // IcoGrid: four outlined squares in a 16-unit box.
                listOf(2.5f to 2.5f, 9f to 2.5f, 2.5f to 9f, 9f to 9f).forEach { (x, y) ->
                    path.addRect(
                        androidx.compose.ui.geometry.Rect(
                            x * s, y * s, (x + 4.5f) * s, (y + 4.5f) * s
                        )
                    )
                }
            } else {
                // IcoList: three plain horizontal lines.
                listOf(4f, 8f, 12f).forEach { y ->
                    path.moveTo(2.5f * s, y * s)
                    path.lineTo(13.5f * s, y * s)
                }
            }
            drawPath(
                path,
                color = color,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
private fun EmptyLibrary(tab: Tab, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (tab == Tab.ALL) {
            Text(stringResource(R.string.empty_library))
            Text(
                stringResource(R.string.empty_library_hint),
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(onClick = onImport) { Text(stringResource(R.string.add_book)) }
        } else {
            // Per-tab empty message: the library is not empty, this tab just
            // has no books yet (e.g. «you haven't finished any book yet»).
            Text(
                stringResource(
                    when (tab) {
                        Tab.READING -> R.string.empty_tab_reading
                        Tab.COMPLETED -> R.string.empty_tab_completed
                        Tab.RECENT -> R.string.empty_tab_recent
                        Tab.FAVORITES -> R.string.empty_tab_favorites
                        Tab.PRIVATE -> R.string.empty_tab_private
                        Tab.ALL -> R.string.empty_library // unreachable
                    }
                )
            )
        }
    }
}

@Composable
private fun NoSearchResults() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.no_search_results))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: BookEntity,
    title: String,
    onClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    missingFile: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // Upstream f352105: bite taken out of completed books' covers, visible
    // only with the «سوبارو · شکم‌پرستی» (tsugi) character theme.
    showBite: Boolean = false,
    // Tsugi stitch strip under the cover (Windows grid-card parity).
    showStitch: Boolean = false
) {
    Card(
        // Windows parity: 1dp border, 2dp primary when selected
        border = if (selected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
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
                    // Crop fills the box completely (no side gaps); the box
                    // is 0.75 aspect so covers fill edge-to-edge.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        // Cover flush with card edges; top corners rounded to
                        // match the card's shape.
                        .clip(
                            RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = 0.dp,
                                bottomEnd = 0.dp
                            )
                        )
                )
                if (showBite) {
                    BiteOverlay(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(64.dp)
                    )
                }
                if (book.isFavorite) {
                    // Windows grid card: accent-filled circle, white heart.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(AppIconKind.Heart, tint = Color.White, size = 17.dp, filled = true)
                    }
                }
                if (missingFile) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        AppIcon(AppIconKind.Warning, tint = MaterialTheme.colorScheme.error, size = 16.dp)
                    }
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(6.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            AppIconKind.Check,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            size = 15.dp
                        )
                    }
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
                    // Windows card button: near-white circle, IcoDots.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xE6FFFFFF))
                            .clickable { onMenuClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(AppIconKind.Dots, tint = Color(0xFF4C4766), size = 16.dp, filled = true)
                    }
                }
            }
            if (showStitch) {
                StitchStrip(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(10.dp)
                )
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
                        stringResource(R.string.percent_value, percentDisplay(book))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BookRow(
    book: BookEntity,
    title: String,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    missingFile: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
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
                        stringResource(R.string.percent_value, percentDisplay(book))
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(
                    progress = { (progressPercent(book) / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            if (missingFile) {
                AppIcon(AppIconKind.Warning, tint = MaterialTheme.colorScheme.error, size = 15.dp, modifier = Modifier.padding(end = 4.dp))
            }
            if (book.isFavorite) {
                AppIcon(AppIconKind.Heart, tint = MaterialTheme.colorScheme.primary, size = 15.dp, filled = true, modifier = Modifier.padding(end = 4.dp))
            }
            if (selected) {
                AppIcon(AppIconKind.Check, tint = MaterialTheme.colorScheme.secondary, size = 14.dp)
            }
            IconButton(onClick = onMenuClick) {
                AppIcon(AppIconKind.Dots, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp, filled = true)
            }
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

/**
 * True when the text's first strong-directional character is RTL
 * (Arabic/Persian/Hebrew). Empty or direction-neutral text defaults to RTL,
 * matching the app's Persian UI.
 */
private fun isRtlText(s: String): Boolean {
    for (c in s) {
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return true
}

@Composable
private fun ProfileNameDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    // Cursor starts AFTER the text (standard edit-field behavior) and
    // the field's layout direction follows the text's own script: a
    // Latin name inside an RTL layout used to show its cursor on the
    // side that looks like the end to an RTL reader while logically
    // sitting at the start, so backspace appeared to do nothing; with
    // a script-matched direction the visual and logical positions
    // agree, for Latin and Persian names alike.
    var field by remember(initial) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.labelMedium)
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (isRtlText(field.text)) LayoutDirection.Rtl
                        else LayoutDirection.Ltr
                ) {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it; error = false },
                        singleLine = true,
                        isError = error,
                        // Align with the text's own direction: Persian names sit
                        // on the right, Latin names on the left.
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = if (isRtlText(field.text)) TextAlign.Right else TextAlign.Left
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = field.text.trim()
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
private fun AboutDialog(onDismiss: () -> Unit, onShowGuide: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    // WPF parity: channel / donate / boost rows (DarkMessageBox.cs:636-638).
    val chLabel = stringResource(R.string.link_channel)
    val doLabel = stringResource(R.string.link_donate)
    val boLabel = stringResource(R.string.link_boost)
    val links = remember(chLabel, doLabel, boLabel) {
        listOf(
            Triple(chLabel, "t.me/NoveIFa", "https://t.me/NoveIFa"),
            Triple(doLabel, "Daramet.com/NovelFa", "http://Daramet.com/NovelFa"),
            Triple(boLabel, "t.me/NoveIFa?boost", "https://t.me/NoveIFa?boost")
        )
    }
    val copiedMsg = stringResource(R.string.copied)
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
                Spacer(modifier = Modifier.height(12.dp))
                // WPF parity: channel / donate / boost rows, each with
                // an open button and a copy-to-clipboard button.
                for ((label, display, url) in links) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = display,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }
                        }) {
                            Text(stringResource(R.string.open_link))
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(url))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.copy_link))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onShowGuide) {
                    Text(stringResource(R.string.view_guide))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        }
    )
}
