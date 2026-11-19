package com.ethran.notable.ui.views

import android.util.Log
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.ui.res.pluralStringResource
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.viewmodels.MoveTarget
import compose.icons.feathericons.Book
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronsDown
import compose.icons.feathericons.ChevronsUp
import compose.icons.feathericons.Copy
import compose.icons.feathericons.CornerUpRight
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Layout
import compose.icons.feathericons.Search
import compose.icons.feathericons.Trash2
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.editor.utils.EpdRefreshArbiter
import com.ethran.notable.R
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NConfirmDialog
import com.ethran.notable.ui.design.NTopBar
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Image
import com.ethran.notable.ui.components.FastScroller
import com.ethran.notable.ui.components.PageCard
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.viewmodels.PagesUiState
import com.ethran.notable.ui.viewmodels.PagesViewModel
import com.ethran.notable.utils.InsertionSlot
import com.ethran.notable.utils.ReorderableGridItem
import com.ethran.notable.utils.computeInsertionSlotRect
import com.ethran.notable.utils.rememberReorderableGridState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


object PagesDestination : NavigationDestination {
    override val route = "books"
    const val BOOK_ID_ARG = "bookId"

    // Route: books/{bookId}/pages
    val routeWithArgs = "$route/{$BOOK_ID_ARG}/pages"

    fun createRoute(bookId: String) = "$route/$bookId/pages"
}


@Composable
fun PagesView(
    bookId: String,
    goToLibrary: (String?) -> Unit,
    goToEditor: (String, String) -> Unit,
    viewModel: PagesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }


    // The observed notebook was deleted (or never existed) while this screen was open — leave
    // instead of rendering a stale/empty book. Guards the observe-then-delete race.
    LaunchedEffect(state.bookMissing) {
        if (state.bookMissing) goToLibrary(null)
    }

    PagesContent(
        state = state,
        onBack = goToLibrary,
        onOpenPage = { pageId -> goToEditor(pageId, bookId) },
        onGenerateThumbnails = viewModel::generateThumbnailsForCurrentBook,
        onReorder = { id, to -> viewModel.reorderPage(bookId, id, to) },
        onDeletePage = viewModel::deletePage,
        onDuplicatePage = viewModel::duplicatePage,
        onAddPageAfter = { viewModel.newPageInBook(bookId, it) },
        selection = PagesSelectionUi(
            onSelecting = viewModel::setSelecting,
            onToggle = viewModel::toggleSelected,
            onSelectAll = viewModel::selectAll,
            onDelete = viewModel::deleteSelected,
            onDuplicate = viewModel::duplicateSelected,
            onMoveWithin = { toStart -> viewModel.moveSelectedWithin(bookId, toStart) },
            onMoveTo = viewModel::moveSelectedTo,
            loadTargets = viewModel::moveTargets,
            onTemplate = viewModel::setSelectedBackground,
            onAddWithTemplate = { index, type, value -> viewModel.newPageWithBackground(bookId, index, type, value) },
        ))
}

/** Selecting pages and adding one with a template (docs/plans/library-features.md, 35-39). */
data class PagesSelectionUi(
    val onSelecting: (Boolean) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onMoveWithin: (toStart: Boolean) -> Unit = {},
    val onMoveTo: (notebookId: String, title: String) -> Unit = { _, _ -> },
    val loadTargets: suspend () -> List<MoveTarget> = { emptyList() },
    val onTemplate: (type: String, value: String?) -> Unit = { _, _ -> },
    val onAddWithTemplate: (index: Int, type: String, value: String?) -> Unit = { _, _, _ -> },
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesContent(
    state: PagesUiState,
    onBack: (String?) -> Unit,
    onOpenPage: (String) -> Unit,
    onGenerateThumbnails: () -> Unit,
    onReorder: (String, Int) -> Unit,
    onDeletePage: (String) -> Unit,
    onDuplicatePage: (String) -> Unit,
    onAddPageAfter: (Int) -> Unit,
    selection: PagesSelectionUi = PagesSelectionUi(),
) {
    if (state.isLoading) return
    androidx.activity.compose.BackHandler(enabled = state.selecting) { selection.onSelecting(false) }
    var confirmDeleteSelected by rememberSaveable { mutableStateOf(false) }
    var pickingNotebook by rememberSaveable { mutableStateOf(false) }
    var pickingTemplate by rememberSaveable { mutableStateOf(false) }
    // "Add page" in edit mode: where it goes, then which template (null: not adding).
    var addingAt by rememberSaveable { mutableStateOf<Int?>(null) }
    var addingTemplate by rememberSaveable { mutableStateOf(false) }
    if (confirmDeleteSelected) NConfirmDialog(
        title = pluralStringResource(R.plurals.pages_delete_selected_title, state.selected.size, state.selected.size),
        message = stringResource(R.string.pages_delete_message),
        confirmText = stringResource(R.string.page_menu_delete),
        cancelText = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = { confirmDeleteSelected = false; selection.onDelete() },
        onDismiss = { confirmDeleteSelected = false })
    if (pickingNotebook) NotebookPicker(selection.loadTargets, onPick = { t -> pickingNotebook = false; selection.onMoveTo(t.id, t.title) },
        onDismiss = { pickingNotebook = false })
    if (pickingTemplate) BackgroundSelector(
        initialPageBackgroundType = state.defaultBackgroundType, initialPageBackground = state.defaultBackground,
        notebookId = state.bookId,
        onChange = { type, value -> pickingTemplate = false; selection.onTemplate(type, value) },
        onClose = { pickingTemplate = false })
    addingAt?.let { index ->
        if (addingTemplate) BackgroundSelector(
            initialPageBackgroundType = state.defaultBackgroundType, initialPageBackground = state.defaultBackground,
            notebookId = state.bookId,
            onChange = { type, value -> addingAt = null; addingTemplate = false; selection.onAddWithTemplate(index, type, value ?: state.defaultBackground) },
            onClose = { addingAt = null; addingTemplate = false })
        else NDialog(
            title = stringResource(R.string.pages_add_title, index + 1), onDismiss = { addingAt = null }, maxWidth = 480.dp,
            actions = { NButton(stringResource(R.string.dialog_cancel), { addingAt = null }, style = NButtonStyle.Quiet) },
        ) {
            NMenuItem(stringResource(R.string.pages_add_usual), onClick = { addingAt = null; onAddPageAfter(index) }, icon = FeatherIcons.FilePlus)
            NMenuItem(stringResource(R.string.pages_add_choose), onClick = { addingTemplate = true }, icon = FeatherIcons.Layout)
        }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // --- 1. State for Edit Mode ---
    var isEditMode by rememberSaveable { mutableStateOf(false) }

    val reorderState = rememberReorderableGridState()
    val density = LocalDensity.current

    // Initial focus on current page
    LaunchedEffect(state.openPageId, state.pageIds) {
        val index = state.pageIds.indexOf(state.openPageId)
        if (index >= 0 && !reorderState.wareReordered) {
            Log.d("PagesView", "Initial focus on page $index")
            gridState.scrollToItem(index)
        }
    }


    var pendingDeletePageId by rememberSaveable { mutableStateOf<String?>(null) }

    pendingDeletePageId?.let { id ->
        NConfirmDialog(
            title = stringResource(R.string.pages_delete_title),
            message = stringResource(R.string.pages_delete_message),
            confirmText = stringResource(R.string.page_menu_delete),
            cancelText = stringResource(R.string.dialog_cancel),
            destructive = true,
            onConfirm = {
                onDeletePage(id)
                pendingDeletePageId = null
            },
            onDismiss = {
                pendingDeletePageId = null
            })
    }

    val c = NotableTheme.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        // Back goes to the folder that holds the notebook (the library at the root).
        val parent = state.folderList.lastOrNull()
        val parentTitle = parent?.title ?: stringResource(R.string.home_view_name)
        NTopBar(
            title = state.bookTitle.ifBlank { stringResource(R.string.pages_title) },
            meta = parentTitle,
            onBack = { onBack(parent?.id) },
            backDescription = stringResource(R.string.pages_back_to, parentTitle),
        ) {
            NButton(
                stringResource(R.string.pages_generate_previews),
                onClick = onGenerateThumbnails,
                style = NButtonStyle.Quiet,
                icon = FeatherIcons.Image,
            )
            NButton(
                stringResource(R.string.pages_select),
                onClick = { selection.onSelecting(!state.selecting); isEditMode = false },
                icon = FeatherIcons.CheckSquare,
                selected = state.selecting,
            )
            NButton(
                stringResource(R.string.pages_edit_mode),
                onClick = { isEditMode = !isEditMode; selection.onSelecting(false) },
                icon = FeatherIcons.Edit2,
                selected = isEditMode,
            )
            if (state.openPageId != null) {
                NButton(
                    stringResource(R.string.pages_jump_to_current),
                    onClick = {
                        val idx = state.pageIds.indexOf(state.openPageId)
                        if (idx >= 0) scope.launch { gridState.scrollToItem(idx) }
                    },
                    style = NButtonStyle.Primary,
                )
            }
        }

        if (state.selecting) PagesSelectionBar(
            count = state.selected.size, total = state.pageIds.size, selection = selection,
            onDelete = { confirmDeleteSelected = true }, onPickNotebook = { pickingNotebook = true },
            onPickTemplate = { pickingTemplate = true },
        )
        Box(
            Modifier
                .padding(horizontal = NotableSpace.xl)
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    reorderState.containerOriginInRoot =
                        IntOffset(r.left.roundToInt(), r.top.roundToInt())
                }) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(148.dp),
                verticalArrangement = Arrangement.spacedBy(NotableSpace.xl),
                horizontalArrangement = Arrangement.spacedBy(NotableSpace.xl),
                // Room for the fast scroller on the right.
                contentPadding = PaddingValues(top = NotableSpace.xl, bottom = NotableSpace.xl, end = NotableSpace.xxxl),
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        val r = coords.boundsInRoot()
                        reorderState.gridOriginInRoot =
                            IntOffset(r.left.roundToInt(), r.top.roundToInt())
                    }
                    .autoEInkAnimationOnScroll()) {
                itemsIndexed(
                    items = state.pageIds, key = { _, id -> id }) { pageIndex, pageId ->
                    val isOpen = pageId == state.openPageId

                    ReorderableGridItem(
                        itemId = pageId,
                        index = pageIndex,
                        gridState = gridState,
                        state = reorderState,
                        // --- 3. Disable reordering when not in edit mode ---
                        isEnabled = isEditMode,
                        onDrop = { _, to, droppedId ->
                            onReorder(droppedId, to)
                        }) { touchMod ->
                        PageCard(
                            pageId = pageId,
                            pageIndex = pageIndex,
                            isOpen = isOpen,
                            // --- 4. Pass edit mode state to PageCard ---
                            isEditMode = isEditMode,
                            isReorderDragging = reorderState.draggingId == null,
                            touchModifier = touchMod,
                            onOpen = {
                                onOpenPage(pageId)
                            },
                            onDelete = { pendingDeletePageId = pageId },
                            onDuplicate = { onDuplicatePage(pageId) },
                            onAddAfter = { addingAt = pageIndex + 1 },
                            selected = if (state.selecting) pageId in state.selected else null,
                            onToggleSelected = { selection.onToggle(pageId) },
                            onLongPress = { selection.onToggle(pageId) })
                    }
                }
            }

            // Insertion highlight overlay (single, global)
            val insertionIndex = reorderState.hoverInsertionIndex
            val slot: InsertionSlot? =
                if (reorderState.draggingId != null && insertionIndex >= 0) computeInsertionSlotRect(
                    gridState.layoutInfo,
                    insertionIndex,
                    barThicknessPx = with(density) { 3.dp.toPx() }.roundToInt()
                ) else null

            if (slot != null) {
                val localOffset = IntOffset(
                    x = slot.offset.x + reorderState.gridOriginInRoot.x - reorderState.containerOriginInRoot.x,
                    y = slot.offset.y + reorderState.gridOriginInRoot.y - reorderState.containerOriginInRoot.y
                )
                val slotWidthDp = with(density) { slot.size.width.toDp() }
                val slotHeightDp = with(density) { slot.size.height.toDp() }

                Box(modifier = Modifier
                    .offset { localOffset }
                    .size(width = slotWidthDp, height = slotHeightDp)
                    .background(c.ink))
            }

            // Drag proxy overlay — match on-screen size
            val draggingId = reorderState.draggingId
            if (draggingId != null) {
                val originPair = reorderState.itemBounds[draggingId]
                if (originPair != null) {
                    val (originRoot, sizePx) = originPair

                    val sizeOfDragging = 0.5f
                    val wDp = with(density) { sizePx.width.toDp() } * sizeOfDragging
                    val hDp = with(density) { sizePx.height.toDp() } * sizeOfDragging
                    val isDraggingPageOpen = draggingId == state.openPageId

                    val localStartX =
                        originRoot.x - reorderState.containerOriginInRoot.x + reorderState.dragDelta.x
                    val localStartY =
                        originRoot.y - reorderState.containerOriginInRoot.y + reorderState.dragDelta.y

                    Box(modifier = Modifier
                        .offset { IntOffset(localStartX, localStartY) }
                        .size(width = wDp, height = hDp)
                        .background(c.paper)) {
                        // PagePreview fills the proxy without adding extra outer borders
                        PagePreview(
                            modifier = Modifier
                                .matchParentSize()
                                .border(
                                    if (isDraggingPageOpen) NotableSize.strong else NotableSize.hairline,
                                    if (isDraggingPageOpen) c.ink else c.rule,
                                    NotableShape.paper
                                ), pageId = draggingId
                        )
                    }

                    // Auto-scroll near edges while dragging
                    val pointerY = originRoot.y + reorderState.dragDelta.y
                    val viewportTop = reorderState.gridOriginInRoot.y
                    val viewportBottom = viewportTop + gridState.layoutInfo.viewportSize.height
                    val edge = 64
                    LaunchedEffect(pointerY) {
                        when {
                            pointerY < viewportTop + edge -> gridState.scrollBy(-40f)
                            pointerY > viewportBottom - edge -> gridState.scrollBy(40f)
                        }
                    }
                }
            }

            if (state.pageIds.size > 30) {
                var scrollerRefreshHandle: EpdRefreshArbiter.Handle? by remember {
                    mutableStateOf(null)
                }
                FastScroller(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    state = gridState,
                    itemCount = state.pageIds.size,
                    getVisibleIndex = { gridState.firstVisibleItemIndex },
                    onDragStart = {
                        if (scrollerRefreshHandle == null)
                            scrollerRefreshHandle = EpdRefreshArbiter.acquire("fast-scroller")
                    },
                    onDragEnd = {
                        scrollerRefreshHandle?.release()
                        scrollerRefreshHandle = null
                    })
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PagesPreview() {
    PagesContent(
        state = PagesUiState(
        pageIds = listOf("p1", "p2", "p3"), openPageId = "p2", isLoading = false
    ),
        onBack = {},
        onOpenPage = {},
        onGenerateThumbnails = {},
        onReorder = { _, _ -> },
        onDeletePage = {},
        onDuplicatePage = {},
        onAddPageAfter = {})
}


/** The actions on selected pages, under the top bar (docs/plans/library-features.md, 35-37). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagesSelectionBar(
    count: Int, total: Int, selection: PagesSelectionUi,
    onDelete: () -> Unit, onPickNotebook: () -> Unit, onPickTemplate: () -> Unit,
) {
    val any = count > 0
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = NotableSpace.xl, vertical = NotableSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
        verticalArrangement = Arrangement.spacedBy(NotableSpace.sm),
    ) {
        Text(pluralStringResource(R.plurals.select_count, count, count), style = NotableTheme.type.heading,
            color = NotableTheme.colors.ink, modifier = Modifier.align(Alignment.CenterVertically))
        if (count < total) NButton(stringResource(R.string.select_all), selection.onSelectAll, style = NButtonStyle.Quiet)
        NButton(stringResource(R.string.pages_move_to_notebook), onPickNotebook, icon = FeatherIcons.CornerUpRight, enabled = any)
        NButton(stringResource(R.string.pages_move_start), { selection.onMoveWithin(true) }, icon = FeatherIcons.ChevronsUp, enabled = any)
        NButton(stringResource(R.string.pages_move_end), { selection.onMoveWithin(false) }, icon = FeatherIcons.ChevronsDown, enabled = any)
        NButton(stringResource(R.string.pages_duplicate), selection.onDuplicate, icon = FeatherIcons.Copy, enabled = any)
        NButton(stringResource(R.string.pages_template), onPickTemplate, icon = FeatherIcons.Layout, enabled = any)
        NButton(stringResource(R.string.pages_delete), onDelete, icon = FeatherIcons.Trash2, style = NButtonStyle.Danger, enabled = any)
        NButton(stringResource(R.string.details_done), { selection.onSelecting(false) }, style = NButtonStyle.Primary)
    }
}

/** Which notebook to move the pages to: every other one, filtered by what is typed. */
@Composable
private fun NotebookPicker(load: suspend () -> List<MoveTarget>, onPick: (MoveTarget) -> Unit, onDismiss: () -> Unit) {
    var targets by remember { mutableStateOf<List<MoveTarget>?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { targets = load() }
    NDialog(
        title = stringResource(R.string.pages_move_to_notebook), onDismiss = onDismiss, maxWidth = 560.dp,
        actions = { NButton(stringResource(R.string.dialog_cancel), onDismiss, style = NButtonStyle.Quiet) },
    ) {
        NTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = stringResource(R.string.pages_move_filter), leadingIcon = FeatherIcons.Search,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(NotableSpace.sm))
        val list = targets
        if (list == null) Text(stringResource(R.string.book_loading), style = NotableTheme.type.meta, color = NotableTheme.colors.secondary)
        else {
            val f = filter.trim().lowercase()
            val shown = if (f.isEmpty()) list else list.filter { it.title.lowercase().contains(f) || it.path.lowercase().contains(f) }
            if (shown.isEmpty()) Text(stringResource(R.string.pages_move_none), style = NotableTheme.type.meta, color = NotableTheme.colors.secondary)
            shown.forEach { t -> NMenuItem(t.title, onClick = { onPick(t) }, icon = FeatherIcons.Book, meta = t.path.ifEmpty { null }) }
        }
    }
}
