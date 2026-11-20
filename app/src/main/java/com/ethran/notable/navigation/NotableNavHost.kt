package com.ethran.notable.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.EditorView
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.ui.components.WithRecentsEdgeSwipe
import com.ethran.notable.ui.views.BugReportDestination
import com.ethran.notable.ui.views.BugReportScreen
import com.ethran.notable.ui.views.FlowDestination
import com.ethran.notable.ui.views.FlowView
import com.ethran.notable.ui.views.Library
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.PagesDestination
import com.ethran.notable.ui.views.PagesView
import com.ethran.notable.ui.views.SettingsDestination
import com.ethran.notable.ui.views.SettingsView
import com.ethran.notable.ui.views.SystemInformationDestination
import com.ethran.notable.ui.views.SystemInformationView
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.ui.views.WelcomeView
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("NotableNavHost")

@Composable
fun NotableNavHost(
    exportEngine: ExportEngine,
    appRepository: AppRepository,
    modifier: Modifier = Modifier,
    appNavigator: NotableNavigator
) {

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = appNavigator.navController,
            startDestination = appNavigator.startDestination,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(
                route = LibraryDestination.routeWithArgs,
                arguments = listOf(navArgument(LibraryDestination.FOLDER_ID_ARG) {
                    nullable = true
                }),
            ) {
                WithRecentsEdgeSwipe(goToEditor = { pageId, bId -> appNavigator.goToEditor(pageId, bId) }) {
                    Library(
                        navController = appNavigator.navController,
                        folderId = it.arguments?.getString(LibraryDestination.FOLDER_ID_ARG),
                        goToPage = { pageId -> appNavigator.goToPage(appRepository, pageId) },
                        onCreateNewQuickPage = { folderId ->
                            appNavigator.onCreateNewQuickPage(
                                appRepository,
                                folderId
                            )
                        }
                    )
                }
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = WelcomeDestination.route,
            ) {
                WelcomeView(
                    goToLibrary = { appNavigator.goToLibrary(null) },
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = SystemInformationDestination.route,
            ) {
                SystemInformationView(
                    onBack = { appNavigator.goBack() },
                )
                appNavigator.cleanCurrentPageId()
            }

            composable(
                route = EditorDestination.routeWithArgs,
                arguments = listOf(
                    navArgument(EditorDestination.PAGE_ID_ARG) { type = NavType.StringType },
                    navArgument(EditorDestination.BOOK_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString(EditorDestination.BOOK_ID_ARG)

                val currentPageId = appNavigator.resolveAndSyncPageId(backStackEntry)

                EditorView(
                    goToLibrary = {appNavigator.goToLibrary(it)},
                    goToPages = { bookId -> appNavigator.goToPages(bookId) },
                    goToBugReport = { appNavigator.goToBugReport() },
                    goToEditor = { pageId, bId -> appNavigator.goToEditor(pageId, bId) },
                    bookId = bookId,
                    initialPageId = currentPageId,
                    isQuickNavOpen = appNavigator.isQuickNavOpen,
                    onPageChange = { newPageId ->
                        log.d("onPageChange: $newPageId")
                        appNavigator.onPageChange(
                            backStackEntry,
                            newPageId
                        )
                    }
                )
            }
            composable(
                route = PagesDestination.routeWithArgs,
                arguments = listOf(navArgument(PagesDestination.BOOK_ID_ARG) {
                    /* configuring arguments for navigation */
                    type = NavType.StringType
                }),
            ) {
                val bookId = it.arguments?.getString(PagesDestination.BOOK_ID_ARG)!!
                val goToEditor = { pageId: String, bId: String? -> appNavigator.goToEditor(pageId, bId) }
                WithRecentsEdgeSwipe(goToEditor = goToEditor, currentBookId = bookId) {
                    PagesView(
                        goToLibrary = { folderId -> appNavigator.goToLibrary(folderId) },
                        goToEditor = goToEditor,
                        bookId = bookId,
                    )
                }
            }
            composable(
                route = SettingsDestination.routeWithArgs,
                arguments = listOf(navArgument(SettingsDestination.TAB_ARG) { type = NavType.IntType; defaultValue = 0 }),
            ) {
                SettingsView(
                    onBack = { appNavigator.goBack() },
                    goToWelcome = { appNavigator.goToWelcome() },
                    goToSystemInfo = { appNavigator.goToSystemInfo() },
                    initialTab = it.arguments?.getInt(SettingsDestination.TAB_ARG) ?: 0,
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(route = FlowDestination.route) {
                FlowView(onBack = { appNavigator.goBack() }, goToSettings = { appNavigator.navController.navigate(SettingsDestination.createRoute(SettingsDestination.SYNC_TAB)) })
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = BugReportDestination.route,
            ) {
                BugReportScreen(goBack = { appNavigator.goBack() })
                appNavigator.cleanCurrentPageId()
            }
        }
    }
}