package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.ui.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.PaperAction
import com.ethran.notable.ui.components.PaperIconAction
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.LibraryBar
import com.ethran.notable.ui.components.ResultsLine
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.Paper
import com.ethran.notable.ui.theme.PaperInset
import com.ethran.notable.ui.viewmodels.LibraryBrowse
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.theme.PaperLine
import com.ethran.notable.ui.theme.SecondaryInk
import com.ethran.notable.data.model.BackgroundType
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import compose.icons.feathericons.Search
import compose.icons.feathericons.X
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import com.ethran.notable.ui.viewmodels.RecentNotebooks
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Settings
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
        onPreviewMissing = viewModel::onPreviewRequested

    )
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
    onPreviewMissing: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Paper)) {
        val results = browse.results?.takeIf { query.isNotBlank() }
        NotebookGrid(
            appRepository = appRepository,
            exportEngine = exportEngine,
            books = results?.notebooks ?: browse.sortedBooks ?: uiState.books,
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
                    LibraryBar(query = query, onQueryChange = onQueryChange, browse = browse,
                        onToggleFilter = onToggleFilter, onSort = onSort,
                        onOpenNotebook = { nb -> onNavigateToEditor(RecentNotebooks.pageOf(nb), nb.id) })
                    Spacer(Modifier.height(20.dp))
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
                        Spacer(Modifier.height(16.dp))
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        NotebookImportPanel(
                            onCreateNewNotebook = onCreateNewNotebook,
                            onCreateNewQuickPage = onCreateNewQuickPage,
                            onCreateNewFolder = onCreateNewFolder,
                            onImportPdf = onImportPdf,
                            onImportXopp = onImportXopp,
                            modifier = Modifier.weight(1f),
                        )
                        BadgedBox(badge = {
                            if (!uiState.isLatestVersion) Badge(
                                backgroundColor = Ink,
                                modifier = Modifier.offset((-4).dp, 4.dp)
                            )
                        }) {
                            PaperIconAction(stringResource(R.string.settings_title), FeatherIcons.Settings,
                                onClick = onNavigateToSettings)
                        }
                    }
                    // Folders as a row above the cards, without a heading.
                    if (uiState.folders.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        FolderList(appRepository, uiState.folders, onNavigateToFolder)
                    }
                }
            }
        )
    }
}

@Composable
fun FolderList(
    appRepository: AppRepository,
    folders: List<Folder>,
    onNavigateToFolder: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        if (folders.isNotEmpty()) {
            items(folders, key = { it.id }) { folder ->
                var isFolderSettingsOpen by remember { mutableStateOf(false) }
                if (isFolderSettingsOpen) FolderConfigDialog(
                    appRepository.folderRepository,
                    appRepository.edits,
                    folderId = folder.id,
                    onClose = {
                        log.i("Closing Directory Dialog")
                        isFolderSettingsOpen = false
                    })
                Row(
                    Modifier
                        .combinedClickable(
                            onClick = { onNavigateToFolder(folder.id) },
                            onLongClick = { isFolderSettingsOpen = true })
                        .background(PaperInset)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder, contentDescription = null,
                        Modifier.height(20.dp), tint = Ink
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = folder.title, color = Ink, style = MaterialTheme.typography.body2)
                }
            }
        }
    }
}

@Composable
fun NotebookGrid(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    books: List<Notebook>,
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
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier.autoEInkAnimationOnScroll()
    ) {
        item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (heading != null || headingContent != null) item(key = "notebooks-heading", span = { GridItemSpan(maxLineSpan) }) {
            if (headingContent != null) headingContent() else Text(
                text = heading ?: stringResource(R.string.home_notebooks),
                style = MaterialTheme.typography.h6,
                color = Ink
            )
        }
        if (emptyText != null) item(key = "notebooks-empty", span = { GridItemSpan(maxLineSpan) }) {
            Text(emptyText, style = MaterialTheme.typography.body2, color = SecondaryInk)
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
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperAction(stringResource(R.string.library_new_notebook), onCreateNewNotebook,
            icon = FeatherIcons.FilePlus, primary = true)
        PaperAction(stringResource(R.string.library_new_quick_page), onCreateNewQuickPage,
            icon = FeatherIcons.Plus)
        PaperAction(stringResource(R.string.library_new_folder), onCreateNewFolder,
            icon = FeatherIcons.FolderPlus)
        PaperAction(stringResource(R.string.library_import), onImport, icon = FeatherIcons.Upload)
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
        isLatestVersion = true,
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
        isLatestVersion = false, // Will show the red badge on the settings icon
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
