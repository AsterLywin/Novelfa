package com.novelfa.reader

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end instant-read navigation (standing rule, 2026-11-25):
 * drives the REAL [OpeningScreen] + the REAL
 * LibraryViewModel.openExternal under Robolectric — no mocks of the
 * flow itself — and asserts what the user sees: after opening a file
 * from outside the app, the back stack holds exactly the reader,
 * never the library on top of it (the 2026-11-25 back-stop race),
 * and backing out of the reader does not resurface the file.
 *
 * The reader destination is a stub: the navigation under test is
 * identical, but the real reader (WebView / PDFium) cannot run on
 * the JVM. Parsing and rendering are covered by the parser tests
 * and the headless-Chromium sim; this test owns the connected flow.
 *
 * No Compose test rule here, deliberately: createComposeRule()
 * launches a bare ComponentActivity that Robolectric cannot resolve
 * (it is in no manifest), and createAndroidComposeRule<MainActivity>
 * refuses setContent because MainActivity already sets its own
 * content in onCreate. So the test launches MainActivity itself via
 * ActivityScenario, swaps its content to the test NavHost, and pumps
 * the main looper + background threads by hand until the navigation
 * lands (or the 30s budget runs out).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NovelfaApplication::class)
class InstantReadNavigationTest {

    @Test
    fun instantOpen_landsOnReaderAlone_backDoesNotReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "instant-test").apply { mkdirs() }
        val file = File(dir, "test.md").apply {
            writeText("# Test Book\n\nSome body text here.\n", Charsets.UTF_8)
        }
        // A REAL NavHostController, not TestNavHostController: the
        // test controller ships a TEST navigator for the
        // "composable" destination and never parses the
        // start-destination route's arguments (args stayed null
        // even with navArgument declared). The real controller is
        // what the app uses (rememberNavController), so it is also
        // the more faithful test double — but a bare
        // NavHostController(context) has NO navigators at all:
        // rememberNavController is what registers ComposeNavigator
        // + DialogNavigator (javap-verified in 2.7.7), so the test
        // must register them itself, or NavHost's createGraph
        // throws during composition and nothing ever composes.
        val navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            navigatorProvider.addNavigator(DialogNavigator())
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                NavHost(
                    navController = navController,
                    startDestination = Routes.openingRoute(Uri.fromFile(file))
                ) {
                    // The argument declarations MUST mirror the real
                    // NavGraph: without navArgument("uri") the {uri}
                    // placeholder is never parsed out of the route and
                    // the entry's arguments are empty (OpeningScreen
                    // then fails on an empty uri).
                    composable(
                        route = Routes.OPENING,
                        arguments = listOf(navArgument("uri") { type = NavType.StringType })
                    ) { entry ->
                        // Pass directUri like the real app does
                        // (MainActivity hands OpeningScreen the
                        // in-memory Uri; the route arg is only the
                        // process-death fallback). Relying on the
                        // route arg here proved fragile under test:
                        // NavHost's startDestination never delivered
                        // it (args stayed null), so the screen failed
                        // on an empty uri and the test could never
                        // reach the reader.
                        OpeningScreen(
                            directUri = Uri.fromFile(file),
                            encodedUri = entry.arguments?.getString("uri") ?: "",
                            navController = navController
                        )
                    }
                    composable(Routes.LIBRARY) { Box(Modifier.testTag("library")) }
                    composable(
                        route = Routes.READER,
                        arguments = listOf(navArgument("bookId") { type = NavType.StringType })
                    ) { Box(Modifier.testTag("reader")) }
                }
            }
        }
        // openExternal runs in a LaunchedEffect on IO; pump the
        // main looper (recomposition + navigation) and give the
        // background threads real time (the import itself) until
        // the reader is current, or the budget runs out. idleFor,
        // not idle: Compose frames are Choreographer callbacks
        // scheduled for the next vsync (~16ms out), and plain idle()
        // only runs tasks due at the current virtual time — so no
        // frame ever fires and the LaunchedEffect never starts.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (navController.currentDestination?.route == Routes.READER) break
            Thread.sleep(50)
        }
        // The stack must hold exactly the reader: before the
        // 2026-11-25 fix the back-stop effect put the library
        // ON TOP of it, so the user saw the library instead.
        // (currentBackStack's first entry is the NavGraph itself
        // with a null route, so the real destinations are the
        // non-null ones.)
        assertEquals(Routes.READER, navController.currentDestination?.route)
        val routes = navController.currentBackStack.value.map { it.destination.route }
        assertEquals(listOf(Routes.READER), routes.filterNotNull())
        assertTrue(routes.none { it == Routes.LIBRARY })
        // Backing out of the reader must not resurface the file:
        // there is nothing left to pop to (the opening route was
        // popped inclusively), so popBackStack reports false.
        assertFalse(navController.popBackStack())
        scenario.close()
    }
}
