package com.novelfa.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) { Text("Library") }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { Text("Reader") }
        composable(Routes.SETTINGS) { Text("Settings") }
    }
}
