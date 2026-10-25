package com.novelfa.reader

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.novelfa.reader.reader.ReaderScreen
import com.novelfa.reader.settings.PinManager
import com.novelfa.reader.settings.PrefsStorePinPrefs
import com.novelfa.reader.settings.PrefsStoreSupportPrefs
import com.novelfa.reader.settings.SettingsRoute
import com.novelfa.reader.settings.SettingsViewModel
import com.novelfa.reader.settings.SupportTracker
import com.novelfa.reader.theme.PrefsStoreRankPrefs

@Composable
fun NavGraph(
    openBookUri: Uri? = null,
    onBookUriConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
            // Task 8: real PIN gate (was NoPinGate) + rank/support prefs.
            val viewModel = remember {
                LibraryViewModel(
                    db.bookDao(),
                    Importer(context, db),
                    PinManager(PrefsStorePinPrefs(prefs)),
                    cacheDir = context.cacheDir,
                    prefs = prefs
                )
            }
            LibraryScreen(
                onOpenBook = { id -> navController.navigate("reader/$id") },
                viewModel = viewModel,
                onOpenCollections = { navController.navigate(Routes.COLLECTIONS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                pendingImportUri = openBookUri,
                onImportUriConsumed = onBookUriConsumed
            )
        }
        composable(Routes.COLLECTIONS) {
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
            val viewModel = remember {
                LibraryViewModel(
                    db.bookDao(),
                    Importer(context, db),
                    PinManager(PrefsStorePinPrefs(prefs)),
                    cacheDir = context.cacheDir,
                    prefs = prefs
                )
            }
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
            ReaderScreen(
                bookId = bookId,
                dao = db.bookDao(),
                onClose = { navController.popBackStack() },
                support = supportTracker
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val db = remember { (context.applicationContext as NovelfaApplication).db }
            val prefs = remember { PrefsStore(context.applicationContext) } // applicationContext: the DataStore delegate is process-wide; an Activity context would leak on recreation
            val settingsVm = remember {
                SettingsViewModel(
                    prefs = prefs,
                    pinManager = PinManager(PrefsStorePinPrefs(prefs)),
                    dao = db.bookDao(),
                    rankPrefs = PrefsStoreRankPrefs(prefs),
                    support = SupportTracker(PrefsStoreSupportPrefs(prefs))
                )
            }
            val fonts = remember { FontStore(context) }
            SettingsRoute(
                viewModel = settingsVm,
                fontStore = fonts,
                onClose = { navController.popBackStack() }
            )
        }
    }
}
