package com.novelfa.reader

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.data.RoomBookRepository
import com.novelfa.reader.library.LibraryViewModel
import com.novelfa.reader.library.LibraryViewModelFactory
import com.novelfa.reader.library.PdfPasswordDialog
import com.novelfa.reader.library.rememberPdfPasswordPrompt
import com.novelfa.reader.settings.PinManager
import com.novelfa.reader.settings.PrefsStorePinPrefs

/**
 * Instant reading (خوندن درجا): a file opened from outside the app
 * (file manager, Telegram, share) lands here, never on the library.
 *
 * ComicScreen model: its ImgActivity IS the VIEW receiver and opens
 * the file directly in the reader — no library, no import dialog,
 * copy only as a fallback when the source cannot be read. This
 * screen is Novelfa's equivalent: it registers the file in place
 * ([LibraryViewModel.openExternal], head-only sniff, no full copy)
 * and navigates straight to `reader/{bookId}`. The only thing the
 * user sees before the reader is a spinner — never the home screen,
 * never the import/copy dialog. On failure it stays here with the
 * reason, a retry, and a way back to the library.
 */
private sealed interface OpeningState {
    data object Opening : OpeningState
    data class Failed(val reason: String, val detail: String? = null) : OpeningState
}

@Composable
fun OpeningScreen(
    directUri: Uri? = null,
    encodedUri: String,
    navController: NavController
) {
    val context = LocalContext.current
    // The Uri object itself is the primary source: the route arg
    // (Base64) is only a fallback for process-death restore. A
    // Telegram open once reached the importer with an EMPTY uri
    // ("No content provider:" and no address), and the route
    // round-trip is the only lossy step in the chain — so the
    // in-memory Uri wins whenever it is present. Keyless remember:
    // the first value is kept even after the intake consumes the
    // Uri (a keyed remember would re-resolve to the empty fallback).
    val uri = remember { directUri ?: Routes.decodeOpeningUri(encodedUri) }
    val db = remember { (context.applicationContext as NovelfaApplication).db }
    val prefs = remember { PrefsStore(context.applicationContext) }
    val viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(
            RoomBookRepository(db.bookDao()),
            Importer(context, db, prefs),
            PinManager(PrefsStorePinPrefs(prefs)),
            cacheDir = context.cacheDir,
            filesDir = context.filesDir,
            prefs = prefs
        )
    )
    val pdfPasswordPrompt = rememberPdfPasswordPrompt()
    var state by remember { mutableStateOf<OpeningState>(OpeningState.Opening) }
    // Retry bumps this key to re-run the open below.
    var attempt by remember { mutableIntStateOf(0) }
    // Set on a successful open; see the back-stop effect below.
    var openedId by remember { mutableStateOf<String?>(null) }

    // A password question left hanging when this screen goes away
    // must not outlive it: the importer's await would wait forever.
    DisposableEffect(Unit) {
        onDispose { pdfPasswordPrompt.dismissPending() }
    }

    // Already opened once: re-entering this screen (back from the
    // reader, if the popUpTo below ever missed) must not re-open
    // the file — the back-stop effect sends the user to the library.
    LaunchedEffect(uri, attempt) {
        if (openedId != null) return@LaunchedEffect
        val target = uri
        if (target == null || target.toString().isEmpty()) {
            // An empty URI (lost on the way in) used to reach the
            // importer and fail as "No content provider:" with no
            // address at all — fail here instead, naming the loss.
            state = OpeningState.Failed(
                context.getString(R.string.import_error_generic),
                "empty file uri received"
            )
            return@LaunchedEffect
        }
        state = OpeningState.Opening
        when (val result = viewModel.openExternal(target, pdfPasswordPrompt.provider)) {
            is LibraryViewModel.OpenExternalResult.Opened -> {
                openedId = result.bookId
                navController.navigate("reader/${result.bookId}") {
                    popUpTo(Routes.OPENING) { inclusive = true }
                }
            }
            is LibraryViewModel.OpenExternalResult.Failed -> {
                // The attempted URI rides along in the detail: on
                // a phone it is the only way to tell an empty/lost
                // URI from a provider that really does not resolve.
                val diag = listOfNotNull(
                    result.detail,
                    "URI: $target"
                ).joinToString("\n")
                state = OpeningState.Failed(result.reason, diag)
            }
        }
    }
    // Back-stop: if this screen is ever visible again after a
    // successful open (reader popped back onto it because the
    // popUpTo above missed), don't re-open — go to the library.
    // On the normal path this screen has already left composition
    // by the time openedId is set, so the effect is cancelled
    // before it can run; if it does run first, its library
    // navigation is still a correct landing — the reader navigation
    // that follows just pushes on top of it.
    LaunchedEffect(openedId) {
        if (openedId != null) {
            navController.navigate(Routes.LIBRARY) {
                popUpTo(Routes.OPENING) { inclusive = true }
            }
        }
    }

    PdfPasswordDialog(pdfPasswordPrompt)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (val current = state) {
            is OpeningState.Opening -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.opening_book),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is OpeningState.Failed -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = current.reason,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    // Technical cause, when it adds anything: the
                    // user cannot send logcat, so the exact failing
                    // step has to be readable on this screen.
                    if (current.detail != null && current.detail != current.reason) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = current.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { attempt++ }) {
                        Text(stringResource(R.string.opening_retry))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            navController.navigate(Routes.LIBRARY) {
                                popUpTo(Routes.OPENING) { inclusive = true }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.opening_back_to_library))
                    }
                }
            }
        }
    }
}
