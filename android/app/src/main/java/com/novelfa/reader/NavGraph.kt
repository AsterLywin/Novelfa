package com.novelfa.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.Room
import com.novelfa.reader.data.Importer
import com.novelfa.reader.data.ReaderDb
import com.novelfa.reader.library.LibraryScreen
import com.novelfa.reader.library.LibraryViewModel

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val context = LocalContext.current
            val db = remember {
                Room.databaseBuilder(context, ReaderDb::class.java, "reader.db").build()
            }
            val viewModel = remember {
                LibraryViewModel(db.bookDao(), Importer(context, db))
            }
            LibraryScreen(
                onOpenBook = { id -> navController.navigate("reader/$id") },
                viewModel = viewModel
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { Text("Reader") }
        composable(Routes.SETTINGS) { Text("Settings") }
    }
}
