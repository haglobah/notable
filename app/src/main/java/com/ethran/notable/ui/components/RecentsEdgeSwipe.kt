package com.ethran.notable.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.ui.RecentsPanel
import com.ethran.notable.gestures.EdgeSwipe
import com.ethran.notable.gestures.edgeSwipeGestures
import com.ethran.notable.ui.viewmodels.RecentNotebooks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the recents panel shows while it is open. */
data class RecentsContent(val recent: List<Notebook>, val favorites: List<Page>)

@HiltViewModel
class RecentsPanelViewModel @Inject constructor(
    private val appRepository: AppRepository,
) : ViewModel() {
    private val _content = MutableStateFlow<RecentsContent?>(null)

    /** The open panel's content; null while it is closed. */
    val content: StateFlow<RecentsContent?> = _content.asStateFlow()

    fun open() {
        viewModelScope.launch(Dispatchers.IO) {
            _content.value = RecentsContent(
                recent = RecentNotebooks.of(appRepository.bookRepository.getAll()),
                favorites = appRepository.pageRepository.getByIds(GlobalAppSettings.current.quickNavPages),
            )
        }
    }

    fun close() {
        _content.value = null
    }
}

/**
 * The editor's recents panel on screens outside the editor: a swipe in from the upper left edge
 * opens it over [content]. The other edges are left to the screen.
 */
@Composable
fun WithRecentsEdgeSwipe(
    goToEditor: (pageId: String, bookId: String?) -> Unit,
    currentBookId: String? = null,
    viewModel: RecentsPanelViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val open by viewModel.content.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().edgeSwipeGestures(
            enabled = open == null,
            edges = setOf(EdgeSwipe.LeftUpper),
        ) { viewModel.open() }
    ) {
        content()
        open?.let { panel ->
            BackHandler(onBack = viewModel::close)
            RecentsPanel(
                recent = panel.recent,
                favorites = panel.favorites,
                currentBookId = currentBookId,
                currentPageId = null,
                onOpenNotebook = { nb ->
                    viewModel.close()
                    goToEditor(RecentNotebooks.pageOf(nb), nb.id)
                },
                onOpenPage = { p ->
                    viewModel.close()
                    goToEditor(p.id, p.notebookId)
                },
                onClose = viewModel::close,
            )
        }
    }
}
