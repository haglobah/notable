package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.ethran.notable.ui.viewmodels.QuickNavUiState
import com.ethran.notable.ui.viewmodels.QuickNavViewModel
import com.ethran.notable.ui.viewmodels.ScrubberState
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import compose.icons.FeatherIcons
import compose.icons.feathericons.Heart
import compose.icons.feathericons.Image
import io.shipbook.shipbooksdk.ShipBook

private val logQuickNav = ShipBook.getLogger("QuickNav")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickNavEntryPoint {
    fun thumbnailBackfillQueue(): ThumbnailBackfillQueue
    fun snackDispatcher(): SnackDispatcher
}



@Composable
fun QuickNav(
    appRepository: AppRepository,
    currentPageId: String?,
    quickNavSourcePageId: String?,
    onClose: () -> Unit,
    goToPage: (String) -> Unit,
    goToFolder: (String?) -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPoints.get(context.applicationContext, QuickNavEntryPoint::class.java)
    }
    val thumbnailBackfillQueue = entryPoint.thumbnailBackfillQueue()

    // Provide the ViewModel using a custom Factory to inject appRepository
    val viewModel: QuickNavViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickNavViewModel(
                appRepository = appRepository,
                thumbnailBackfillQueue = thumbnailBackfillQueue,
                snackDispatcher = entryPoint.snackDispatcher()
            ) as T
        }
    })

    // Observe the UI State lifecycle-safely
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Load data when the page changes
    LaunchedEffect(currentPageId) {
        viewModel.loadPageData(currentPageId)
    }

    QuickNavContent(
        appRepository = appRepository,
        currentPageId = currentPageId,
        uiState = uiState,
        onClose = {
            logQuickNav.d("outside tap -> close")
            onClose()
        },
        onNavigateBreadcrumb = { goToFolder(it) },
        onToggleFavorite = viewModel::toggleFavorite,
        onGenerateBookPreviews = viewModel::generateThumbnailsForCurrentBook,
        onScrubStart = viewModel::onScrubStart,
        onScrubPreview = viewModel::onScrubPreview,
        onScrubEnd = viewModel::onScrubEnd,
        onReturnClick = { viewModel.onReturnClick(quickNavSourcePageId) },
        goToPage = { goToPage(it) },
    )
}

@Composable
fun QuickNavContent(
    appRepository: AppRepository?,
    currentPageId: String?,
    uiState: QuickNavUiState,
    onClose: () -> Unit,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onGenerateBookPreviews: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPreview: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    onReturnClick: () -> Unit,
    goToPage: (String) -> Unit,
) {
    // uiState may still describe the previous page while its load is in flight.
    // The scrubber is gated on notebook membership, not page identity, so it stays mounted across a scrub.
    val isStateForPage = uiState.pageId != null && uiState.pageId == currentPageId
    val scrubber = uiState.scrubber
    val scrubberIndex = scrubber?.pageIds?.indexOf(currentPageId) ?: -1

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        // Tap outside to dismiss
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .inkClickable(role = null, onClick = onClose)
        )

        // Top divider above the sheet
        NDivider(strong = true)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NotableTheme.colors.paper)
                .padding(horizontal = NotableSpace.lg, vertical = NotableSpace.md)
        ) {

            // Header row: Breadcrumb on the left, Favorite toggle on the right
            QuickNavHeaderRow(
                folders = if (isStateForPage) uiState.breadcrumbFolders else emptyList(),
                isFavorite = isStateForPage && uiState.isCurrentPageFavorite,
                canToggleFavorite = isStateForPage,
                canGeneratePreviews = scrubberIndex >= 0,
                onNavigateBreadcrumb = onNavigateBreadcrumb,
                onToggleFavorite = onToggleFavorite,
                onGenerateBookPreviews = onGenerateBookPreviews
            )

            if (appRepository != null) {
                ShowPagesRow(
                    appRepository = appRepository,
                    pages = uiState.favoritePages,
                    currentPageId = currentPageId,
                    title = "Favorite pages",
                    onSelectPage = { goToPage(it) }
                )
            }

            if (scrubber != null && scrubberIndex >= 0) {
                Spacer(modifier = Modifier.height(NotableSpace.md))

                Row(modifier = Modifier.fillMaxWidth()) {
                    PageHorizontalSliderWithReturn(
                        pageCount = scrubber.pageIds.size,
                        currentIndex = scrubberIndex,
                        favIndexes = scrubber.favoriteIndexes,
                        onDragStart = onScrubStart,
                        onPreviewIndexChanged = onScrubPreview,
                        onDragEnd = onScrubEnd,
                        onReturnClick = onReturnClick
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickNavHeaderRow(
    folders: List<Folder>,
    isFavorite: Boolean,
    canToggleFavorite: Boolean,
    canGeneratePreviews: Boolean,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onGenerateBookPreviews: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BreadCrumb(
            modifier = Modifier.weight(1f),
            folders = folders, fontSize = 16, onSelectFolderId = onNavigateBreadcrumb
        )

        // A favorite page is a tool that is on: the heart button is filled with ink.
        NIconButton(
            icon = FeatherIcons.Heart,
            contentDescription = if (isFavorite) "Remove from favorite pages" else "Add to favorite pages",
            selected = isFavorite,
            onClick = {
                if (canToggleFavorite) {
                    onToggleFavorite()
                } else {
                    logQuickNav.w("favorite toggle ignored, page not loaded")
                }
            })

        Spacer(modifier = Modifier.width(NotableSpace.xs))

        NIconButton(
            icon = FeatherIcons.Image,
            contentDescription = "Generate page previews",
            onClick = {
                if (canGeneratePreviews) {
                    onGenerateBookPreviews()
                } else {
                    logQuickNav.w("generate previews ignored, no book pages")
                }
            }
        )
    }
}


@Preview(showBackground = true)
@Composable
fun QuickNavContentPreview() {
    QuickNavContent(
        appRepository = null,
        currentPageId = "page4",
        uiState = QuickNavUiState(
            favoritePages = listOf(Page(id = "page4")),
            pageId = "page4",
            folderId = "folder1",
            isCurrentPageFavorite = true,
            scrubber = ScrubberState(
                pageIds = List(10) { "page$it" },
                favoriteIndexes = listOf(0, 4, 9),
            ),
        ),
        onClose = {},
        onNavigateBreadcrumb = {},
        onToggleFavorite = {},
        onGenerateBookPreviews = {},
        onScrubStart = {},
        onScrubPreview = {},
        onScrubEnd = {},
        onReturnClick = {},
        goToPage = {},
    )
}