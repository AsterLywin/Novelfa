package com.novelfa.reader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novelfa.reader.data.DiagLog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelfa.reader.R
import com.novelfa.reader.domain.Book
import com.novelfa.reader.data.ImportException
import com.novelfa.reader.data.SUPPORTED_BOOK_MIME_TYPES
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.domain.isCompleted
import com.novelfa.reader.domain.isInProgress
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
import com.novelfa.reader.domain.CharacterTheme
import com.novelfa.reader.domain.CharacterThemes
import com.novelfa.reader.theme.CelebrationParticles
import com.novelfa.reader.theme.EternityMarkIcon
import com.novelfa.reader.domain.CelebrationTier
import com.novelfa.reader.data.PrefsStoreRankPrefs
import com.novelfa.reader.domain.XpLevel
import com.novelfa.reader.domain.XpRank
import com.novelfa.reader.domain.RankService
import com.novelfa.reader.theme.RanksDialog
import com.novelfa.reader.theme.StarRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Calendar
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.offset
import com.novelfa.reader.theme.EmblemWatermark
import com.novelfa.reader.theme.LocalAppPaletteExtras
import com.novelfa.reader.theme.ParticleLayer

internal val TABS = listOf(
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
internal fun GreetingHeader() {
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
    // Secret theme codes (WPF TrackSecretKeys parity): typing BEAKO /
    // EMT / TEA / TSUGI / PEIDE unlocks the hidden themes; the code letters are
    // scrubbed from the search box like the desktop client does.
    var secretUnlocked by remember { mutableStateOf<CharacterTheme?>(null) }
    val secretCodes = remember {
        listOf("BEAKO" to "beatrice", "EMT" to "emilia", "TEA" to "echidna", "TSUGI" to "tsugi", "PEIDE" to "reif")
    }
    val context = LocalContext.current
    // PrefsStore must be built on the application context: the DataStore
    // delegate is process-wide, so an Activity context would leak the
    // Activity on recreation (e.g. in-app language change).
    val prefs = remember(context) { PrefsStore(context.applicationContext) }
    val fileNameTitle by prefs.fileNameTitle.collectAsState(initial = true)
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
    val shown = remember(books, allBooks, sort, fileNameTitle) {
        sortBooks(books, allBooks, sort, fileNameTitle)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val missingFileMsg = stringResource(R.string.book_file_missing)
    val subaruGiftMsg = stringResource(R.string.subaru_gift_message)
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
            // In-place books are missing when their SOURCE is
            // gone, not when their (possibly not-yet-built)
            // working copy is absent — a content:// in-place book
            // that was never opened has no working copy yet and
            // must not be badged as missing. Copy books keep the
            // old rule (the stored file itself is gone).
            shown.filter {
                if (it.importMode == "IN_PLACE") {
                    !viewModel.repo.isSourceAvailable(context, it)
                } else {
                    it.filePath.isBlank() || !File(it.filePath).isFile
                }
            }.map { it.id }.toSet()
        }
    }
    var restartAsk by remember { mutableStateOf<Book?>(null) }
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
    // Import progress: a big DOCX parses for seconds with no other
    // feedback, which reads as "the import does nothing". The shared
    // dialog below stays up until the importer itself finishes
    // (success or failure), and its scrim also blocks a second tap /
    // tab switch mid-import.
    var isImporting by remember { mutableStateOf(false) }
    // The running import job, so the progress dialog's Cancel button
    // can actually stop it: before this, a hung import (e.g. a PDF
    // whose tokenizer never advanced) left a non-dismissible dialog
    // up forever with no way out.
    var importJob by remember { mutableStateOf<Job?>(null) }
    // Current import stage for the progress dialog ("copy", "decrypt",
    // "validate", "parse", "save"; null = no stage to show). Set by
    // importBook through importMultiple; a stuck import's screenshot
    // names the exact step it sits in.
    var importStage by remember { mutableStateOf<String?>(null) }
    // File the dialog is currently on, for the shared dialog's
    // "file N of M" line and file name (same state the collections
    // screen now feeds the same dialog with).
    var importFileName by remember { mutableStateOf<String?>(null) }
    var importFileIndex by remember { mutableStateOf(0) }
    var importFileTotal by remember { mutableStateOf(1) }
    var pendingDuplicateNames by remember { mutableStateOf<List<String>>(emptyList()) }
    // Library id of the book each pending duplicate matches, parallel
    // to pendingDuplicates (see ImportSummary.duplicateBookIds).
    var pendingDuplicateBookIds by remember { mutableStateOf<List<String?>>(emptyList()) }
    // Encrypted-PDF password prompt (manual imports through
    // importUris below). Files opened from outside the app no
    // longer come through here — they go through OpeningScreen,
    // which has its own prompt.
    val pdfPasswordPrompt = rememberPdfPasswordPrompt()

    suspend fun importUris(uris: List<Uri>) {
        // List separator follows the UI language — never a hardcoded «، ».
        val sep = if (java.util.Locale.getDefault().language.startsWith("en")) ", " else "، "
        // Batch failures used to show only a count ("1 failed"), so a
        // failing import looked like "nothing happened". Name the
        // failed files (first 3) with their reasons.
        fun failedPart(summary: LibraryViewModel.ImportSummary): String? {
            if (summary.failed <= 0) return null
            val base = failedFmt.format(summary.failed)
            if (summary.failedDetails.isEmpty()) return base
            val shown = summary.failedDetails.take(3)
                .joinToString(sep) { "${it.name}: ${it.reason}" }
            return "$base ($shown)"
        }
        isImporting = true
        importStage = null
        importFileName = null
        importFileIndex = 0
        importFileTotal = uris.size
        importJob = currentCoroutineContext()[Job]
        // The summary message is shown AFTER the dialog closes, never
        // inside the try: showSnackbar suspends until the message is
        // dismissed, so showing it here used to keep the dialog up
        // for the whole snackbar duration even though the book was
        // already saved — the "imported already, dialog hasn't
        // noticed" symptom. The dialog now closes exactly when the
        // importer returns, and the message follows it.
        var snackbarMessage: String? = null
        try {
            val summary = viewModel.importMultiple(
                uris,
                passwordProvider = pdfPasswordPrompt.provider,
                onStage = { importStage = it },
                onFile = { index, name ->
                    importFileIndex = index
                    importFileName = name
                }
            )
            // If duplicates found, ask for confirmation instead of silently skipping
            if (summary.duplicates > 0 && summary.duplicateUris.isNotEmpty()) {
                pendingDuplicates = summary.duplicateUris
                pendingDuplicateNames = summary.duplicateNames
                pendingDuplicateBookIds = summary.duplicateBookIds
                // Still show the summary for added/failed
                val parts = mutableListOf<String>()
                if (summary.added > 0) parts.add(addedFmt.format(summary.added))
                if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
                failedPart(summary)?.let { parts.add(it) }
                if (parts.isNotEmpty()) snackbarMessage = parts.joinToString(sep)
            } else {
                val parts = mutableListOf<String>()
                if (summary.added > 0) parts.add(addedFmt.format(summary.added))
                if (summary.duplicates > 0) parts.add(dupFmt.format(summary.duplicates))
                if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
                failedPart(summary)?.let { parts.add(it) }
                if (parts.isNotEmpty()) snackbarMessage = parts.joinToString(sep)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportException) {
            // DIAGNOSTIC (temporary): surface the real cause in the UI so a
            // failing import can be root-caused from a screenshot alone.
            // Remove once the import failure is understood and fixed.
            val cause = e.cause
            val detail = (cause?.javaClass?.simpleName ?: "no-cause") +
                ": " + (cause?.message ?: e.message)
            snackbarMessage = e.messageFa + "\n" + detail
        } catch (e: Exception) {
            // Anything else (Room errors, unexpected throws) must not crash
            // the app: show a generic message with the diagnostic detail.
            val detail = (e.javaClass.simpleName ?: "no-cause") + ": " + (e.message ?: "")
            snackbarMessage =
                context.getString(R.string.import_error_generic) + "\n" + detail
        } finally {
            importJob = null
            importStage = null
            importFileName = null
            importFileIndex = 0
            isImporting = false
            // A cancelled import waiting on a PDF password used to
            // leave the password dialog up after this dialog closed.
            pdfPasswordPrompt.dismissPending()
        }
        snackbarMessage?.let { snackbar.showSnackbar(it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importUris(uris)
            }
        }
    }
    // Relink picker: a single file, re-pointing an in-place book
    // whose source moved or whose permission was lost. It is a
    // separate launcher from the import picker because its result
    // updates a row instead of creating one.
    var relinkTargetId by remember { mutableStateOf<String?>(null) }
    val relinkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = relinkTargetId
        relinkTargetId = null
        if (uri != null && targetId != null) {
            scope.launch {
                val ok = viewModel.relinkBook(targetId, uri)
                snackbar.showSnackbar(
                    if (ok) context.getString(R.string.relink_done)
                    else context.getString(R.string.relink_failed)
                )
            }
        }
    }
    val launchImport = { picker.launch(SUPPORTED_BOOK_MIME_TYPES) }

    if (isImporting) {
        // Shared dialog (same one the collections screen uses):
        // Novelfa Card style, file name, "file N of M", live stage,
        // and a Cancel that cancels the running import job.
        ImportProgressDialog(
            fileName = importFileName,
            fileIndex = importFileIndex,
            fileTotal = importFileTotal,
            stage = importStage,
            onCancel = { importJob?.cancel() }
        )
    }

    // (Files opened from outside the app used to be imported here
    // via a LaunchedEffect on pendingImportUri — library first, then
    // the import dialog, and no navigation to the reader at all.
    // That path now lives in OpeningScreen: the file opens straight
    // into the reader and this screen never sees it.)
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
    // All collection names (book-anchored + manual empty ones, distinct
    // and sorted): the assign dialog picks from these instead of
    // making the user type a name every time.
    val collectionNames by viewModel.collectionNames.collectAsState(initial = emptyList())
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
            RankHook.refreshXpRank(viewModel.repo, rankPrefs, prefs.getFinishedBooksRestored())
                ?.let { xpRankUp = it }
            xpLevel = RankHook.xpLevelNow(viewModel.repo, rankPrefs, prefs.getFinishedBooksRestored())
        } catch (e: Exception) {
            // Was runCatching{} with no onFailure: a failing refresh silently
            // skipped the celebration with nothing in logcat. Log it so a
            // missing celebration is diagnosable instead of invisible.
            DiagLog.w("NovelfaXp", "XP celebration refresh failed", e)
        }
    }
    // One-time XP ledger rekeying (UUID -> content key): runs in the
    // background, never blocking home; a failure here must not break
    // the library, and the flag stays unset so it retries next visit.
    LaunchedEffect(Unit) {
        runCatching {
            RankHook.rekeyXpLedgerIfNeeded(viewModel.repo, rankPrefs)
        }.onFailure { e ->
            DiagLog.w("NovelfaXp", "XP ledger rekeying failed", e)
        }
    }
    // Re-check XP on resume (gateTick): returning from the reader does not
    // change `tab`, so the effect above would not re-run — and the
    // celebration earned mid-reading would wait for a tab switch. This
    // effect must NOT increment gateTick itself (that would loop).
    LaunchedEffect(gateTick) {
        try {
            RankHook.refreshXpRank(viewModel.repo, rankPrefs, prefs.getFinishedBooksRestored())
                ?.let { xpRankUp = it }
            xpLevel = RankHook.xpLevelNow(viewModel.repo, rankPrefs, prefs.getFinishedBooksRestored())
        } catch (e: Exception) {
            // See the LaunchedEffect(tab) block above: never swallow this
            // silently, or a missing celebration leaves no trace.
            DiagLog.w("NovelfaXp", "XP celebration refresh failed", e)
        }
    }
    // Also re-check on resume: the PIN can be set/removed in Settings, and
    // the back navigation doesn't change `tab`, so the gate above wouldn't
    // re-run — leaving the private tab permanently locked after PIN removal.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) gateTick++
            // Auto-relock the private shelf when the app leaves the
            // foreground: an in-memory-only unlock used to stay unlocked
            // indefinitely after the user switched to another app.
            // (Opening a system picker also stops the activity, so the
            // shelf relocks then too — secure, and one tap reopens it.)
            if (event == Lifecycle.Event.ON_STOP) {
                PinSession.unlocked = false
                unlocked = false
            }
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
    // Avatar version stamp: every pick (and every backup restore)
    // overwrites the SAME file (avatars/avatar.jpg), so the path string
    // never changes on a re-pick. Without a stamp, the header would
    // not recompose and Coil — which keys its cache by path — would
    // keep serving the previous bitmap, so a newly picked photo never
    // appeared. The file's mtime + length change on every rewrite, so
    // they are the version (consumed by ProfileHeader).
    val avatarSignature = avatarPath.takeIf { it.isNotBlank() }?.let { File(it) }
        ?.takeIf { it.isFile }?.let { "${it.lastModified()}-${it.length()}" } ?: ""
    var avatarOpen by remember { mutableStateOf(false) }
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        // Validate + downscale instead of copying raw: a
                        // picked photo used to land undecoded and uncapped
                        // (multi-MB, any format) as "avatar.jpg", and an
                        // undecodable file then showed a blank circle.
                        val resolver = context.contentResolver
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        // NOTE: decodeStream returns null in bounds-only
                        // mode, so its result must NOT feed a ?: — the
                        // old code threw "cannot open image" on every
                        // pick, valid image or not. Only a null stream
                        // throws here; an undecodable file is caught by
                        // the bounds check below.
                        val boundsStream = resolver.openInputStream(uri)
                            ?: throw IOException("cannot open image")
                        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            throw IOException("not an image")
                        }
                        var sample = 1
                        while (bounds.outWidth / sample > 1024 ||
                            bounds.outHeight / sample > 1024
                        ) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bitmap = resolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        } ?: throw IOException("cannot decode image")
                        try {
                            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
                            val dest = File(dir, "avatar.jpg")
                            dest.outputStream().use { out ->
                                if (!bitmap.compress(
                                        Bitmap.CompressFormat.JPEG, 90, out
                                    )
                                ) throw IOException("cannot encode avatar")
                            }
                            dest.absolutePath
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                result.onSuccess { path -> prefs.setAvatarPath(path) }
                    .onFailure { snackbar.showSnackbar(avatarCopyErr) }
                avatarOpen = false
            }
        }
    }

    var menuFor by remember { mutableStateOf<Book?>(null) }
    var assignFor by remember { mutableStateOf<Book?>(null) }
    var resetFor by remember { mutableStateOf<Book?>(null) }
    var removeFor by remember { mutableStateOf<Book?>(null) }

    val finishedCount = remember(allBooks) { allBooks.count { isCompleted(it) } }
    val rankTitle = remember(xpLevel, isEnglishUi) {
        (xpLevel ?: XpRank.levelFor(0)).title.displayName(isEnglishUi)
    }
    val rankStarFill = remember(xpLevel) {
        (xpLevel ?: XpRank.levelFor(0)).starFill
    }
    // Precompute display titles once per book-list emission (O(n), not O(n²)).
    val titleMap = remember(allBooks, fileNameTitle) {
        displayTitleMap(allBooks, fileNameTitle)
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

    // Character-theme particle layer (WPF parity): drawn behind the library
    // content when the active character theme defines particles and the user
    // enabled them in the theme picker.
    val charThemeId by prefs.charTheme.collectAsState(initial = "")
    val particlesOn by prefs.themeParticles.collectAsState(initial = false)
    val particlesLowEnd by prefs.themeParticlesLowEnd.collectAsState(initial = false)
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
            light = appThemeKey == "light",
            lowEnd = particlesLowEnd
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
            if (selection.isNotEmpty()) {
                // Contextual action bar: it REPLACES the normal
                // header (same bar, same background) instead of
                // adding a second coloured slab below it — the old
                // full-width purple SelectionBar doubled the header
                // and shoved the whole library down on long-press.
                // Trash uses the app icon set (AppIconKind.Trash),
                // not the hand-drawn canvas trash of the old bar.
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.n_selected, selection.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            AppIcon(
                                AppIconKind.Close,
                                tint = MaterialTheme.colorScheme.onSurface,
                                size = 19.dp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    actions = {
                        IconButton(onClick = { deleteConfirm = true }) {
                            AppIcon(
                                AppIconKind.Trash,
                                tint = MaterialTheme.colorScheme.onSurface,
                                size = 19.dp
                            )
                        }
                    }
                )
            } else {
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
            }
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
                    avatarSignature = avatarSignature,
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
                    useFileNameTitle = fileNameTitle,
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
                            // Lava cracks: completed books only, reif theme only.
                            showLava = charThemeId == "reif" && isCompleted(book),
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
                    avatarSignature = avatarSignature,
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
                    useFileNameTitle = fileNameTitle,
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
                        // The persisted throttle must reset with it, or
                        // the next PIN would inherit the old lockout.
                        pinManager.setThrottleState(0, 0L)
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
                    // First-run gift: the Subaru theme, applied the
                    // moment the name is picked — only when no theme
                    // is set yet, so an existing choice is never
                    // overridden. (This dialog runs on first run
                    // only; renames use the dialog below and never
                    // gift.) The dialog closes before the snackbar
                    // shows: showSnackbar suspends for its duration.
                    val gifted = charThemeId.isBlank()
                    if (gifted) prefs.setCharTheme("subaru")
                    welcomeOpen = false
                    if (gifted) snackbar.showSnackbar(subaruGiftMsg)
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
        // Custom dialog (not AlertDialog) so the celebration burst
        // can live behind the text: AlertDialog has no slot for a
        // background canvas, and particles drawn outside it would
        // sit behind the dialog scrim, invisible. The canvas is
        // hit-test invisible, so the buttons stay tappable. Strings,
        // acknowledge logic and theme activation are unchanged.
        Dialog(onDismissRequest = { dismissXpUp() }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box {
                    // matchParentSize, not fillMaxSize: in a Box
                    // this sizes the particle canvas to the Box
                    // as determined by its sibling (the Column),
                    // so the dialog wraps the content height
                    // instead of stretching to the screen.
                    CelebrationParticles(
                        tier = up.tier,
                        modifier = Modifier.matchParentSize()
                    )
                    Column(modifier = Modifier.padding(24.dp)) {
                        // Eternity tiers: the earned mark itself, large
                        // and gold, with its name underneath — the dialog
                        // never named which mark was earned before.
                        // Non-eternity tiers have no mark (mark == null).
                        val mark = up.toLevel.mark
                        if (mark != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                EternityMarkIcon(
                                    markIndex = mark.index,
                                    size = 72.dp,
                                    earned = true
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = mark.displayName(isEnglishUi),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    // Same accent as the mark itself.
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                        Text(
                            text = stringResource(tierTitleRes),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
                        // embedded in the message text. Bigger than the
                        // old 20.dp so the celebration reads at a glance;
                        // eternity tiers keep smaller stars under the hero
                        // mark instead of competing with it.
                        Spacer(modifier = Modifier.height(10.dp))
                        StarRow(
                            fill = up.toLevel.starFill,
                            starSize = if (mark != null) 24.dp else 28.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (best != null) {
                                TextButton(onClick = { dismissXpUp() }) {
                                    Text(stringResource(R.string.no))
                                }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    // Guard (same as SettingsViewModel.setCharTheme):
                                    // best is genuinely unlocked by
                                    // construction (RankHook's fresh
                                    // filter), but never store a theme
                                    // the unlock check rejects.
                                    if (best != null && RankService(rankPrefs).isUnlocked(best)) {
                                        prefs.setCharTheme(best.id)
                                    }
                                    RankHook.acknowledgeXpCelebration(rankPrefs, up.toXp)
                                    xpRankUp = null
                                }
                            }) {
                                Text(
                                    if (best != null) stringResource(R.string.yes)
                                    else stringResource(R.string.xp_celebration_ok)
                                )
                            }
                        }
                    }
                }
            }
        }
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
                        // Guard (same as SettingsViewModel.setCharTheme):
                        // the theme was secret-unlocked a moment ago in
                        // this flow, but never store a theme the unlock
                        // check rejects.
                        if (RankService(rankPrefs).isUnlocked(theme)) {
                            prefs.setCharTheme(theme.id)
                        }
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
            text = { Text(stringResource(R.string.start_over_prompt, displayTitle(book, allBooks, fileNameTitle))) },
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
            useFileNameTitle = fileNameTitle,
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
            onRelink = if (target.importMode == "IN_PLACE") {
                {
                    relinkTargetId = target.id
                    menuFor = null
                    relinkPicker.launch(
                        arrayOf(
                            "application/epub+zip", "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/plain", "text/markdown",
                            "application/zip", "application/x-cbz",
                            "application/vnd.comicbook+zip"
                        )
                    )
                }
            } else null,
            onRemove = {
                removeFor = target
                menuFor = null
            }
        )
    }

    assignFor?.let { target ->
        AssignCollectionDialog(
            current = target.collectionName,
            collections = collectionNames,
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
            text = { Text(displayTitle(target, allBooks, fileNameTitle)) },
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
            text = { Text(displayTitle(target, allBooks, fileNameTitle)) },
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
                        .joinToString("\n") { displayTitle(it, allBooks, fileNameTitle) }
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

    // Duplicate import: the overwrite confirmation that importUris() arms
    // (shared dialog — the collections screen uses the same one).
    if (pendingDuplicates.isNotEmpty()) {
        DuplicateOverwriteDialog(
            names = pendingDuplicateNames,
            onDismiss = {
                pendingDuplicates = emptyList()
                pendingDuplicateNames = emptyList()
                pendingDuplicateBookIds = emptyList()
            },
            onConfirm = {
                val uris = pendingDuplicates
                val existingIds = pendingDuplicateBookIds
                pendingDuplicates = emptyList()
                pendingDuplicateNames = emptyList()
                pendingDuplicateBookIds = emptyList()
                // Overwrites run through the same shared dialog
                // as a fresh import (this path used to run with
                // no progress UI and no Cancel at all), and its
                // summary is shown after the dialog closes, for
                // the same snackbar-delay reason as importUris.
                scope.launch {
                    isImporting = true
                    importStage = null
                    importFileName = null
                    importFileIndex = 0
                    importFileTotal = uris.size
                    importJob = currentCoroutineContext()[Job]
                    var message: String? = null
                    try {
                        val summary = viewModel.overwriteDuplicates(
                            uris,
                            existingIds = existingIds,
                            passwordProvider = pdfPasswordPrompt.provider,
                            onStage = { importStage = it },
                            onFile = { index, name ->
                                importFileIndex = index
                                importFileName = name
                            }
                        )
                        val parts = mutableListOf<String>()
                        if (summary.added > 0) parts.add(addedFmt.format(summary.added))
                        if (summary.progressRestored > 0) parts.add(restoredFmt.format(summary.progressRestored))
                        if (summary.failed > 0) {
                            val base = failedFmt.format(summary.failed)
                            val sep2 = if (isEnglishUi) ", " else "، "
                            val shown = summary.failedDetails.take(3)
                                .joinToString(sep2) { "${it.name}: ${it.reason}" }
                            parts.add(if (summary.failedDetails.isEmpty()) base else "$base ($shown)")
                        }
                        if (parts.isNotEmpty()) {
                            message = parts.joinToString(if (isEnglishUi) ", " else "، ")
                        }
                    } catch (e: CancellationException) {
                        // Cancelled overwrite: no message, no book.
                        throw e
                    } finally {
                        importJob = null
                        importStage = null
                        importFileName = null
                        importFileIndex = 0
                        isImporting = false
                        pdfPasswordPrompt.dismissPending()
                    }
                    message?.let { snackbar.showSnackbar(it) }
                }
            }
        )
    }

    PdfPasswordDialog(pdfPasswordPrompt)

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

