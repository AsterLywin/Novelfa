package com.novelfa.reader

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.PrefsStore
import com.novelfa.reader.library.CollectionsScreen
import com.novelfa.reader.library.LibraryScreen
import com.novelfa.reader.library.LibraryViewModel
import com.novelfa.reader.library.LibraryViewModelFactory
import com.novelfa.reader.reader.PdfReaderRoute
import com.novelfa.reader.settings.PinManager
import com.novelfa.reader.settings.PrefsStorePinPrefs
import com.novelfa.reader.settings.PrefsStoreSupportPrefs
import com.novelfa.reader.settings.SettingsRoute
import com.novelfa.reader.settings.SettingsViewModel
import com.novelfa.reader.settings.SettingsViewModelFactory
import com.novelfa.reader.settings.SupportTracker
import com.novelfa.reader.data.PrefsStoreRankPrefs
import com.novelfa.reader.data.RoomBookRepository

@Composable
fun NavGraph(
    openBookUri: Uri? = null,
    onBookUriConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    // Instant reading: a file opened from outside the app starts on
    // the opening route, never on the library (see OpeningScreen).
    // The start destination is fixed at FIRST composition only: it
    // must never be recomputed from the consumable openBookUri.
    // NavHost remembers its graph keyed on startDestination, so a
    // recompute (opening route -> library, when the intake effect
    // below consumes the URI) rebuilds the graph and resets the back
    // stack to the library mid-import — the cold-start instant-reading
    // bug (OpeningScreen destroyed ~0.5s in, the import cancelled,
    // library with 0 books, no error). Later intents arrive only
    // through the effect below, never through this value.
    val startDestination = remember {
        openBookUri?.let { Routes.openingRoute(it) } ?: Routes.LIBRARY
    }
    // Warm intent (app already open, e.g. on the reader): the new
    // file must reach the opening route even though the library
    // never composes — the old LaunchedEffect inside LibraryScreen
    // dropped it in exactly that case. Consumed here for cold
    // starts too, so a rotation never re-opens the file.
    var lastHandledExternalUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(openBookUri) {
        val uri = openBookUri ?: return@LaunchedEffect
        if (uri == lastHandledExternalUri) return@LaunchedEffect
        lastHandledExternalUri = uri
        onBookUriConsumed()
        val current = navController.currentDestination?.route
        if (current != null && current != Routes.OPENING) {
            navController.navigate(Routes.openingRoute(uri))
        }
    }
    // Navigation transitions: explicit horizontal slides, mirrored
    // for RTL. The navigation-compose defaults are a 700ms fade +
    // scale crossfade — during it the outgoing and incoming screens
    // blend at partial alpha, so two dense text screens (library
    // and settings) render ON TOP of each other, text over text
    // (caught on video 2026-11-12: ~0.5s of ghost content on
    // library -> settings). A pure slide never blends alpha, so
    // the incoming screen simply covers the outgoing.
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { if (isRtl) -it else it })
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { if (isRtl) it else -it })
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { if (isRtl) it else -it })
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { if (isRtl) -it else it })
        }
    ) {
        composable(
            route = Routes.OPENING,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("uri") ?: ""
            OpeningScreen(encodedUri = encoded, navController = navController)
        }
        composable(Routes.LIBRARY) {
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
            // Task 8: real PIN gate (was NoPinGate) + rank/support prefs.
            // Entry-scoped ViewModel: survives rotation and back-stack
            // navigation, unlike the previous remember construction.
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
            LibraryScreen(
                onOpenBook = { id -> navController.navigate("reader/$id") },
                viewModel = viewModel,
                onOpenCollections = { navController.navigate(Routes.COLLECTIONS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.COLLECTIONS) {
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
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
            CollectionsScreen(
                viewModel = viewModel,
                onOpenBook = { id -> navController.navigate("reader/$id") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
            val supportTracker = remember { SupportTracker(PrefsStoreSupportPrefs(prefs)) }
            val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
            // Reader route: PDF/CBZ/ZIP open in the native image
            // viewer (PDFs via PDFium since 2026-11-01); every
            // other format uses ReaderScreen.
            PdfReaderRoute(
                bookId = bookId,
                repo = RoomBookRepository(db.bookDao()),
                // A reader close must always land somewhere valid.
                // popBackStack() silently returns false when there
                // is nothing to pop to (e.g. the reader is the only
                // entry), and the old code ignored that result — so
                // closing a book that was never loaded (the
                // multi-book archive picker: no book, no content)
                // could strand the user on a blank screen instead
                // of a real destination. Fall back to the library,
                // clearing the dead reader off the stack.
                onClose = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(navController.graph.startDestinationId) {
                                inclusive = true
                            }
                        }
                    }
                },
                support = supportTracker
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
            val settingsVm: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    prefs = prefs,
                    pinManager = PinManager(PrefsStorePinPrefs(prefs)),
                    repo = RoomBookRepository(db.bookDao()),
                    rankPrefs = PrefsStoreRankPrefs(prefs),
                    support = SupportTracker(PrefsStoreSupportPrefs(prefs))
                )
            )
            val fonts = remember { FontStore(context) }
            SettingsRoute(
                viewModel = settingsVm,
                fontStore = fonts,
                onClose = { navController.popBackStack() }
            )
        }
    }
}
