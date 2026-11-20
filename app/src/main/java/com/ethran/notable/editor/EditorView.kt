package com.ethran.notable.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.ui.BookPanelDialog
import com.ethran.notable.editor.ui.JumpBackChip
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.QuickBrowsePanel
import com.ethran.notable.editor.ui.RecentsPanel
import com.ethran.notable.editor.ui.toolbar.CustomViewBar
import com.ethran.notable.editor.ui.toolbar.PositionedToolbar
import com.ethran.notable.gestures.EdgeSwipe
import com.ethran.notable.gestures.edgeSwipeGestures
import com.ethran.notable.ui.viewmodels.RecentNotebooks
import com.ethran.notable.gestures.EditorGestureReceiver
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.InkaTheme
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

private val log = ShipBook.getLogger("EditorView")

object EditorDestination : NavigationDestination {
    override val route = "editor"

    const val PAGE_ID_ARG = "pageId"
    const val BOOK_ID_ARG = "bookId"

    // Unified route: editor/{pageId}?bookId={bookId}
    val routeWithArgs = "$route/{$PAGE_ID_ARG}?$BOOK_ID_ARG={$BOOK_ID_ARG}"

    /**
     * Helper to create the path. If bookId is null, it just won't be appended.
     */
    fun createRoute(pageId: String, bookId: String? = null): String {
        return "$route/$pageId" + if (bookId != null) "?$BOOK_ID_ARG=$bookId" else ""
    }
}


@Composable
fun EditorView(
    initialPageId: String,
    bookId: String?,
    isQuickNavOpen: Boolean,

    // navigation callbacks
    onPageChange: (String) -> Unit,
    goToLibrary: (folderId: String?) -> Unit,
    goToPages: (bookId: String) -> Unit,
    goToBugReport: () -> Unit,
    /** Opens another notebook (or loose page) from the recents panel. */
    goToEditor: (pageId: String, bookId: String?) -> Unit,

    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()

    // Single point of entry for loading book data based on the pageId from Navigation
    // Should not be used for regular page switching
    LaunchedEffect(initialPageId) {
        log.v("EditorView: pageId changed to $initialPageId, loading data")
        viewModel.loadToolbarState(bookId, initialPageId)
    }

    // Sync isQuickNavOpen to ViewModel
    LaunchedEffect(isQuickNavOpen) {
        viewModel.onToolbarAction(ToolbarAction.UpdateQuickNavOpen(isQuickNavOpen))
    }


    val edgeState by viewModel.toolbarState.collectAsStateWithLifecycle()
    BoxWithConstraints(
        // Swipe in from the upper left edge for recents, from the lower left edge back to the
        // library, from the right edge for quick browse.
        Modifier.edgeSwipeGestures(enabled = edgeState.edgePanel == EdgePanel.None) { edge ->
            viewModel.onToolbarAction(
                when (edge) {
                    EdgeSwipe.LeftUpper -> ToolbarAction.OpenEdgePanel(EdgePanel.Recents)
                    EdgeSwipe.LeftLower -> ToolbarAction.NavigateToLibrary
                    EdgeSwipe.Right -> ToolbarAction.OpenEdgePanel(EdgePanel.QuickBrowse)
                }
            )
        }
    ) {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()

        // Here we load initial page into the memory
        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                pageDataManager = viewModel.pageDataManager,
                pageLeases = viewModel.pageLeases,
                appRepository = viewModel.appRepository,
                initialPageId = initialPageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager,
                onPageShown = viewModel::onPageShown,
                onPageLeft = viewModel::onPageLeft,
            )
        }

        val history = remember(page) { viewModel.createHistory(page) }

        val editorControlTower = remember {
            EditorControlTower(
                scope = scope,
                page = page,
                history = history,
                viewModel = viewModel,
                clipboardStore = ClipboardStore,
            )
        }


        // Initialize ViewModel with persisted settings on first composition
        LaunchedEffect(Unit) {
            viewModel.initFromPersistedSettings()
        }

//         val editorControlTower = remember {
//             EditorControlTower(scope, page, history, editorState, context, appRepository).apply { registerObservers() }
        DisposableEffect(editorControlTower) {
            editorControlTower.registerObservers()
            onDispose {
                editorControlTower.unregisterObservers()
            }
        }

        // Debug builds: adb-driven edits through the real PageView and History (DebugEditHook).
        if (com.ethran.notable.BuildConfig.DEBUG) DisposableEffect(page, history) {
            val unregister = DebugEditHook(context, scope, page, history, viewModel, editorControlTower).register()
            onDispose { unregister() }
        }

        // Collect UI Events from ViewModel (navigation )
        LaunchedEffect(Unit) {
            viewModel.uiEvents.collect { event ->
                when (event) {
                    is EditorUiEvent.NavigateToLibrary -> {
                        goToLibrary(event.folderId)
                    }

                    is EditorUiEvent.NavigateToPages -> {
                        goToPages(event.bookId)
                    }

                    EditorUiEvent.NavigateToBugReport -> {
                        goToBugReport()
                    }
                }
            }
        }

        // Collect Canvas Commands from ViewModel
        LaunchedEffect(Unit) {
            viewModel.canvasCommands.collect { command ->
                when (command) {
                    CanvasCommand.Undo -> editorControlTower.undo()
                    CanvasCommand.Redo -> editorControlTower.redo()
                    CanvasCommand.Paste -> editorControlTower.pasteFromClipboard()
                    CanvasCommand.ResetView -> editorControlTower.resetZoomAndScroll()
                    CanvasCommand.ClearAllStrokes -> {
                        CanvasEventBus.clearPageSignal.emit(Unit)
                        snackManager.displaySnack(
                            SnackConf(
                                text = "Cleared all strokes",
                                duration = 2000
                            )
                        )
                    }

                    CanvasCommand.RefreshCanvas -> {
                        CanvasEventBus.reloadFromDb.emit(Unit)
                    }

                    is CanvasCommand.ApplyView -> editorControlTower.applyView(command.view)
                    CanvasCommand.CaptureCustomView -> editorControlTower.captureCustomView()

                    is CanvasCommand.CopyImageToCanvas -> {
                        CanvasEventBus.addImageByUri.value = command.uri
                    }
                }
            }
        }

        val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()

        // Observe pageId changes from ViewModel state for navigation
        LaunchedEffect(viewModel) {
            snapshotFlow { toolbarState.pageId }.filterNotNull().distinctUntilChanged()
                .drop(1) // Skip initial emission from loadBookData
                .collect { newPageId ->
                    log.v("EditorView: snapshotFlow detected pageId change to $newPageId, triggering onPageChange")
                    // update the PageView
                    page.changePage(newPageId)

                    // update the navigation state
                    onPageChange(newPageId)
                }
        }

        // Sync PageView state to ViewModel for Toolbar rendering
        // Only zoom matters here; collecting the whole viewport would recompose on every scroll.
        val zoomFlow = remember(page) { page.viewport.map { it.zoom }.distinctUntilChanged() }
        val zoomLevel by zoomFlow.collectAsStateWithLifecycle(page.zoom)
        LaunchedEffect(zoomLevel) {
            viewModel.setShowResetView(zoomLevel != 1.0f)
        }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.onDispose(page)
            }
        }



        InkaTheme {
            EditorGestureReceiver(actions = editorControlTower)
            EditorSurface(
                viewModel = viewModel,
                page = page,
                history = history
            )
            SelectedBitmap(
                context = context, controlTower = editorControlTower
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrollIndicator(viewModel = viewModel, page = page)
            }
            PositionedToolbar(viewModel = viewModel)
            HorizontalScrollIndicator(viewModel = viewModel, page = page)
            if (toolbarState.isChoosingCustomView) CustomViewBar(viewModel::onToolbarAction)
            val closePanel = { viewModel.onToolbarAction(ToolbarAction.OpenEdgePanel(EdgePanel.None)) }
            when (toolbarState.edgePanel) {
                EdgePanel.Recents -> {
                    val recent by viewModel.recentNotebooks.collectAsStateWithLifecycle()
                    val favorites by viewModel.favoritePages.collectAsStateWithLifecycle()
                    RecentsPanel(
                        recent = recent,
                        favorites = favorites,
                        currentBookId = toolbarState.notebookId,
                        currentPageId = toolbarState.pageId,
                        onOpenNotebook = { nb ->
                            closePanel()
                            if (nb.id != toolbarState.notebookId) goToEditor(RecentNotebooks.pageOf(nb), nb.id)
                        },
                        onOpenPage = { p ->
                            closePanel()
                            if (p.notebookId != null && p.notebookId == toolbarState.notebookId)
                                viewModel.openPageFromPanel(p.id)
                            else if (p.id != toolbarState.pageId) goToEditor(p.id, p.notebookId)
                        },
                        onClose = closePanel,
                    )
                }

                EdgePanel.QuickBrowse -> {
                    val pageIds by viewModel.bookPageIds.collectAsStateWithLifecycle()
                    QuickBrowsePanel(
                        pageIds = pageIds,
                        currentPageId = toolbarState.pageId,
                        onOpenPage = viewModel::openPageFromPanel,
                        onClose = closePanel,
                    )
                }

                EdgePanel.None -> {}
            }
            // Contents and search of the book this notebook was made from.
            val bookPanel by viewModel.bookReading.panel.collectAsStateWithLifecycle()
            bookPanel?.let { panel ->
                BookPanelDialog(
                    panel = panel,
                    onSearch = { viewModel.bookReading.search(it) },
                    onJump = { viewModel.onToolbarAction(ToolbarAction.JumpToPage(it)) },
                    onClose = { viewModel.onToolbarAction(ToolbarAction.CloseBookPanel) },
                    onGoToPage = { viewModel.onToolbarAction(ToolbarAction.GoToPageNumber(it)) },
                    onClearHighlights = { viewModel.onToolbarAction(ToolbarAction.ClearSearchHighlights) },
                )
            }
            // "Back to page 12" after a jump (docs/plans/library-features.md, 32).
            val jumpBack by viewModel.bookReading.jumpBack.collectAsStateWithLifecycle()
            if (bookPanel == null) jumpBack?.let { jump ->
                JumpBackChip(jump, onBack = { viewModel.onToolbarAction(ToolbarAction.JumpBack) },
                    onDismiss = { viewModel.onToolbarAction(ToolbarAction.DismissJumpBack) })
            }
        }
    }
}
