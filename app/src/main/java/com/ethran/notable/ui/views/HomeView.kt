package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.LibraryBar
import com.ethran.notable.ui.components.LibrarySelectionBar
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.ResultsLine
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.ShowFolderSelectionDialog
import com.ethran.notable.ui.dialogs.TrashDialog
import com.ethran.notable.ui.viewmodels.LibraryItem
import com.ethran.notable.ui.viewmodels.LibraryBrowse
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import com.ethran.notable.ui.viewmodels.RecentNotebooks
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.ShipBook


object LibraryDestination : NavigationDestination {
    override val route = "library"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String? = null): String {
        return if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
    }
}

private val log = ShipBook.getLogger("HomeView")

@Composable
fun Library(
    navController: NavController,
    folderId: String? = null,
    goToPage: (String) -> Unit = {},
    onCreateNewQuickPage: (String?) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val quickNotebookId by viewModel.quickNotebookId.collectAsStateWithLifecycle()
    val newlyCreatedBookId by viewModel.newlyCreatedBookId.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    var trashOpen by remember { mutableStateOf(false) }
    if (trashOpen) TrashDialog(
        rows = trash, onRestore = viewModel::restoreFromTrash, onDelete = viewModel::deleteForever,
        onEmpty = viewModel::emptyTrash, onClose = { trashOpen = false },
    )

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    // Show config dialog for newly created notebooks so user can rename immediately
    if (newlyCreatedBookId != null) {
        if (GlobalAppSettings.current.renameOnCreate && uiState.books.any { it.id == newlyCreatedBookId }) {
            NotebookConfigDialog(
                appRepository = viewModel.appRepository,
                exportEngine = viewModel.exportEngine,
                bookId = newlyCreatedBookId!!,
                onClose = { viewModel.clearNewlyCreatedBookId() }
            )
        } else {
            viewModel.clearNewlyCreatedBookId()
        }
    }

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        uiState = uiState,
        query = query,
        browse = browse,
        quickNotebookId = quickNotebookId,
        onToggleFilter = viewModel::toggleFilter,
        onSort = viewModel::setSort,
        onEverywhere = viewModel::setEverywhere,
        onQueryChange = viewModel::setQuery,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        // One tap: a page at the end of the quick notebook, opened (round 5).
        onCreateNewQuickPage = {
            viewModel.openQuickPage { nb, page -> navController.navigate(EditorDestination.createRoute(page, nb)) }
        },
        onCreateNewFolder = viewModel::createNewFolder,
        onDeleteEmptyBook = viewModel::deleteEmptyBook,
        onCreateNewNotebook = viewModel::onCreateNewNotebook,
        onImportPdf = viewModel::onPdfFile,
        onImportXopp = viewModel::onXoppFile,
        onPreviewMissing = viewModel::onPreviewRequested,
        onHideFinished = viewModel::setHideFinished,
        onOpenTrash = { viewModel.loadTrash(); trashOpen = true },
        selection = LibrarySelectionUi(
            selected = selection,
            onToggle = viewModel::toggleSelected,
            onSelectAll = viewModel::selectAll,
            onClear = viewModel::clearSelection,
            onMove = viewModel::moveSelected,
            onSetFinished = viewModel::setSelectedFinished,
            onSetQueued = viewModel::setSelectedQueued,
            onTrash = viewModel::trashSelected,
        ),
    )
}

/** Selecting in the library (docs/plans/library-features.md); [selected] empty: not selecting. */
data class LibrarySelectionUi(
    val selected: Set<LibraryItem> = emptySet(),
    val onToggle: (LibraryItem) -> Unit = {},
    val onSelectAll: (Collection<LibraryItem>) -> Unit = {},
    val onClear: () -> Unit = {},
    val onMove: (String?) -> Unit = {},
    val onSetFinished: (Boolean) -> Unit = {},
    val onSetQueued: (Boolean) -> Unit = {},
    val onTrash: () -> Unit = {},
) {
    val active: Boolean get() = selected.isNotEmpty()
    /** Null when not selecting, else whether [item] is selected: what a card shows. */
    fun stateOf(item: LibraryItem): Boolean? = if (active) item in selected else null
    /** A tap while selecting toggles; a long press starts selecting (or toggles while selecting). */
    fun longPress(item: LibraryItem) = onToggle(item)
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryContent(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    uiState: LibraryUiState,
    query: String = "",
    browse: LibraryBrowse = LibraryBrowse(),
    quickNotebookId: String? = null,
    onToggleFilter: (field: String, value: String) -> Unit = { _, _ -> },
    onSort: (LibrarySearch.SortKey?, Boolean?) -> Unit = { _, _ -> },
    onEverywhere: (Boolean) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewQuickPage: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri) -> Unit,
    onImportXopp: (Uri) -> Unit,
    onPreviewMissing: (String) -> Unit,
    onHideFinished: (Boolean) -> Unit = {},
    onOpenTrash: () -> Unit = {},
    selection: LibrarySelectionUi = LibrarySelectionUi(),
) {
    // Back while selecting ends the selection instead of leaving the folder.
    androidx.activity.compose.BackHandler(enabled = selection.active) { selection.onClear() }
    Column(Modifier.fillMaxSize().background(NotableTheme.colors.paper)) {
        val results = browse.results?.takeIf { query.isNotBlank() }
        val shownBooks = results?.notebooks ?: browse.sortedBooks ?: uiState.books
        // Folders show only outside a search, so only then can they be selected.
        val shownFolders = if (results == null) uiState.folders else emptyList()
        val selectable = shownBooks.filter { it.pageIds.isNotEmpty() }.map { LibraryItem.Book(it.id) } +
            shownFolders.map { LibraryItem.Shelf(it.id) }
        var moving by remember { mutableStateOf(false) }
        var details by remember { mutableStateOf<LibraryItem?>(null) }
        if (moving) {
            val n = selection.selected.size
            ShowFolderSelectionDialog(
                appRepository = appRepository,
                notebookName = "",
                title = pluralStringResource(R.plurals.select_move_title, n, n),
                initialFolderId = uiState.folderId,
                excludeIds = selection.selected.filterIsInstance<LibraryItem.Shelf>().map { it.id }.toSet(),
                onCancel = { moving = false },
                onConfirm = { folder -> moving = false; selection.onMove(folder) },
            )
        }
        when (val d = details) {
            is LibraryItem.Book -> NotebookConfigDialog(appRepository, exportEngine, bookId = d.id, onClose = { details = null })
            is LibraryItem.Shelf -> FolderConfigDialog(appRepository.folderRepository, appRepository.edits, folderId = d.id,
                onClose = { details = null })
            null -> Unit
        }
        NotebookGrid(
            appRepository = appRepository,
            exportEngine = exportEngine,
            books = shownBooks,
            selection = selection,
            isImporting = uiState.isImporting,
            onNavigateToEditor = onNavigateToEditor,
            onDeleteEmptyBook = onDeleteEmptyBook,
            onPreviewMissing = onPreviewMissing,
            modifier = Modifier.weight(1f),
            // Search results come best first and from every folder; a folder lists oldest last.
            newestFirst = results == null && browse.sortedBooks == null,
            folderPaths = results?.folderPaths.orEmpty(),
            // No headings over the library (round 5); searching shows the results line.
            heading = null,
            pinnedFirstId = if (results == null) quickNotebookId else null,
            // Searching: "3 matches in Uni · Search everywhere" instead of the heading.
            headingContent = if (results == null) null else ({
                ResultsLine(
                    count = results.notebooks.size + results.quickPages.size,
                    folderTitle = if (browse.inFolder) uiState.breadcrumbFolders.lastOrNull()?.title else null,
                    everywhere = browse.everywhere, onEverywhere = onEverywhere,
                )
            }),
            emptyText = if (results != null && results.notebooks.isEmpty() && results.quickPages.isEmpty())
                stringResource(R.string.library_search_none) else null,
            header = {
                Column {
                    if (selection.active) {
                        val selectedBooks = shownBooks.filter { LibraryItem.Book(it.id) in selection.selected }
                        LibrarySelectionBar(
                            count = selection.selected.size, books = selectedBooks,
                            canSelectMore = !selection.selected.containsAll(selectable),
                            onSelectAll = { selection.onSelectAll(selectable) }, onClear = selection.onClear,
                            onMove = { moving = true }, onSetFinished = selection.onSetFinished,
                            onSetQueued = selection.onSetQueued, onTrash = selection.onTrash,
                            onDetails = selection.selected.singleOrNull()?.let { only -> { selection.onClear(); details = only } },
                        )
                    } else LibraryBar(query = query, onQueryChange = onQueryChange, browse = browse,
                        onToggleFilter = onToggleFilter, onSort = onSort,
                        onOpenNotebook = { nb -> onNavigateToEditor(RecentNotebooks.pageOf(nb), nb.id) },
                        onHideFinished = onHideFinished,
                        // Navigation lives with the bar, at its far right; a dot marks an update.
                        trailing = {
                            NIconButton(FeatherIcons.Trash2, stringResource(R.string.trash_title), onOpenTrash)
                            SettingsButton(onClick = onNavigateToSettings)
                        })
                    Spacer(Modifier.height(NotableSpace.xl))
                    if (results != null) {
                        if (results.quickPages.isNotEmpty()) ShowPagesRow(
                            appRepository = appRepository,
                            pages = results.quickPages,
                            currentPageId = null,
                            title = stringResource(R.string.home_quick_pages),
                            onSelectPage = goToPage,
                            onPreviewMissing = onPreviewMissing
                        )
                        return@Column
                    }
                    // Inside a folder the breadcrumb stays (navigation); at the root nothing.
                    if (uiState.folderId != null) {
                        BreadCrumb(
                            folders = uiState.breadcrumbFolders,
                            fontSize = 24,
                            onSelectFolderId = onNavigateToFolder
                        )
                        Spacer(Modifier.height(NotableSpace.lg))
                    }
                    NotebookImportPanel(
                        onCreateNewNotebook = onCreateNewNotebook,
                        onCreateNewQuickPage = onCreateNewQuickPage,
                        onCreateNewFolder = onCreateNewFolder,
                        onImportPdf = onImportPdf,
                        onImportXopp = onImportXopp,
                    )
                    // Folders as a row above the cards, without a heading.
                    if (uiState.folders.isNotEmpty()) {
                        Spacer(Modifier.height(NotableSpace.sm))
                        FolderList(appRepository, uiState.folders, onNavigateToFolder, selection)
                    }
                }
            }
        )
    }
}

/** The settings gear. */
@Composable
private fun SettingsButton(onClick: () -> Unit) =
    NIconButton(FeatherIcons.Settings, stringResource(R.string.settings_title), onClick)

@Composable
fun FolderList(
    appRepository: AppRepository,
    folders: List<Folder>,
    onNavigateToFolder: (String) -> Unit,
    selection: LibrarySelectionUi = LibrarySelectionUi(),
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        if (folders.isNotEmpty()) {
            items(folders, key = { it.id }) { folder ->
                // A long press selects the folder (its settings are under Details then).
                val item = LibraryItem.Shelf(folder.id)
                val selected = selection.stateOf(item)
                NButton(folder.title,
                    onClick = { if (selected != null) selection.onToggle(item) else onNavigateToFolder(folder.id) },
                    style = NButtonStyle.Quiet, icon = if (selected == true) FeatherIcons.CheckSquare else FeatherIcons.Folder,
                    selected = selected == true,
                    onLongClick = { selection.longPress(item) })
            }
        }
    }
}


@Composable
fun NotebookGrid(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    books: List<Notebook>,
    selection: LibrarySelectionUi = LibrarySelectionUi(),
    isImporting: Boolean,
    onNavigateToEditor: (String, String) -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onPreviewMissing: (String) -> Unit,
    modifier: Modifier = Modifier,
    newestFirst: Boolean = true,
    folderPaths: Map<String, String> = emptyMap(),
    heading: String? = null,
    headingContent: (@Composable () -> Unit)? = null,
    /** Shown first, before any order: the quick notebook (round 5). */
    pinnedFirstId: String? = null,
    emptyText: String? = null,
    header: @Composable () -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        contentPadding = PaddingValues(horizontal = NotableSpace.gutter, vertical = NotableSpace.xl),
        verticalArrangement = Arrangement.spacedBy(NotableSpace.xl),
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.xl),
        modifier = modifier.autoEInkAnimationOnScroll()
    ) {
        item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (heading != null || headingContent != null) item(key = "notebooks-heading", span = { GridItemSpan(maxLineSpan) }) {
            if (headingContent != null) headingContent() else Text(
                text = heading ?: stringResource(R.string.home_notebooks),
                style = NotableTheme.type.heading,
                color = NotableTheme.colors.ink
            )
        }
        if (emptyText != null) item(key = "notebooks-empty", span = { GridItemSpan(maxLineSpan) }) {
            Text(emptyText, style = NotableTheme.type.body, color = NotableTheme.colors.secondary)
        }

        if (books.isNotEmpty()) {
            val ordered = (if (newestFirst) books.reversed() else books).let { list ->
                val pinned = list.firstOrNull { it.id == pinnedFirstId }
                if (pinned == null) list else listOf(pinned) + (list - pinned)
            }
            items(ordered, key = { "notebook-${it.id}" }) { book ->
                if (book.pageIds.isEmpty()) {
                    if (!isImporting) {
                        EmptyBookWarningHandler(
                            emptyBook = book,
                            onDelete = { onDeleteEmptyBook(book.id) },
                            onDismiss = { })
                    }
                    return@items
                }
                var isSettingsOpen by remember { mutableStateOf(false) }
                NotebookCard(
                    bookId = book.id,
                    title = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    onOpen = { bookId, pageId -> onNavigateToEditor(pageId, bookId) },
                    onOpenSettings = { isSettingsOpen = true },
                    onPreviewMissing = onPreviewMissing,
                    authors = book.bookMeta?.authors.orEmpty(),
                    isBook = book.isBook,
                    finished = book.finishedAt != null,
                    folderPath = folderPaths[book.id],
                    rating = book.rating,
                    queued = book.queuedAt != null,
                    selected = selection.stateOf(LibraryItem.Book(book.id)),
                    onToggleSelected = { selection.onToggle(LibraryItem.Book(book.id)) },
                    onLongPress = { selection.longPress(LibraryItem.Book(book.id)) },
                )

                if (isSettingsOpen) {
                    NotebookConfigDialog(
                        appRepository,
                        exportEngine = exportEngine,
                        bookId = book.id, onClose = { isSettingsOpen = false })
                }
            }
        }
    }
}

@Composable
fun NotebookImportPanel(
    onCreateNewNotebook: () -> Unit,
    onCreateNewQuickPage: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onImportPdf: (Uri) -> Unit,
    onImportXopp: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            log.w("PickVisualMedia: uri is null (user cancelled or provider returned null)")
            return@rememberLauncherForActivityResult
        }
        try {

            val mimeType = context.contentResolver.getType(uri)
            log.d("Selected file mimeType: $mimeType, uri: $uri")
            if (mimeType == "application/pdf" || uri.toString()
                    .endsWith(".pdf", ignoreCase = true)
            ) {
                onImportPdf(uri)
            } else if (com.ethran.notable.io.isEpubFile(mimeType, uri.toString())) {
                // EPUBs are always copied (ImportEngine ignores the link option for them).
                onImportPdf(uri)
            } else {
                onImportXopp(uri)
            }
        } catch (e: Exception) {
            log.e("contentPicker failed: ${e.message}", e)
            snackState.showOrUpdateSnack(SnackConf(text = "Importing failed: ${e.message}"))
        }
    }
    LibraryCreationActions(
        onCreateNewNotebook = onCreateNewNotebook,
        onCreateNewQuickPage = onCreateNewQuickPage,
        onCreateNewFolder = onCreateNewFolder,
        onImport = { launcher.launch(arrayOf("application/x-xopp", "application/gzip",
            "application/octet-stream", "application/pdf", "application/epub+zip")) },
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryCreationActions(
    onCreateNewNotebook: () -> Unit,
    onCreateNewQuickPage: () -> Unit,
    onCreateNewFolder: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
        verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        NButton(stringResource(R.string.library_new_notebook), onCreateNewNotebook,
            icon = FeatherIcons.FilePlus, style = NButtonStyle.Primary)
        NButton(stringResource(R.string.library_new_quick_page), onCreateNewQuickPage,
            icon = FeatherIcons.Plus)
        NButton(stringResource(R.string.library_new_folder), onCreateNewFolder,
            icon = FeatherIcons.FolderPlus)
        NButton(stringResource(R.string.library_import), onImport, icon = FeatherIcons.Upload)
    }
}


@Preview(
    showBackground = true,
    name = "Library - Default State",
    widthDp = 800,
    heightDp = 1200
)
@Composable
fun LibraryContentPreview() {
    // 1. Create a dummy UI state with mock data
    val mockUiState = LibraryUiState(
        folderId = null,
        isImporting = false,
        breadcrumbFolders = listOf(
            // Optional: Add mock breadcrumbs if you want to preview nested folder state
             Folder(id = "root", title = "Home", parentFolderId = null)
        ),
        folders = listOf(
            // Adjust constructor arguments based on your exact entity definition
            Folder(id = "folder_1", title = "Work Notes", parentFolderId = null),
            Folder(id = "folder_2", title = "Personal", parentFolderId = null)
        ),
        books = listOf(
            // Needs pageIds to render the card (empty books show a warning)
            Notebook(id = "book_1", title = "Meeting Minutes", pageIds = listOf("page1", "page2")),
            Notebook(id = "book_2", title = "Journal", pageIds = listOf("page3"))
        ),
        singlePages = emptyList() // Populate with mock Page() objects if you want to see Quick Pages
    )

    // 2. Render the stateless component with empty lambdas
//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _ -> },
//        onImportXopp = {})
}

@Suppress("UnusedVariable")
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Preview(showBackground = true, name = "Library - Update Available & Importing")
@Composable
fun LibraryContentUpdatePreview() {
    val mockUiState = LibraryUiState(
        folderId = "folder_1",
        isImporting = true,      // Will hide the delete warning for empty books
        breadcrumbFolders = emptyList(),
        folders = emptyList(),
        books = emptyList(),
        singlePages = emptyList()
    )

//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _ -> },
//        onImportXopp = {})
}

/** Made from a PDF or EPUB: imports give the notebook its book as the default background. */
private val Notebook.isBook: Boolean
    get() = when (BackgroundType.fromKey(defaultBackgroundType)) {
        BackgroundType.AutoPdf, is BackgroundType.Pdf -> true
        else -> false
    }
