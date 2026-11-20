package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.deletePage
import com.ethran.notable.io.copyLink
import com.ethran.notable.io.pageLinkTitle
import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.components.ScaledPopup
import com.ethran.notable.ui.design.NMenuDivider
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.nSurface
import com.ethran.notable.utils.onSuccess
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.Copy
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Link
import compose.icons.feathericons.Trash2
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PageMenuEntryPoint {
    fun pageSaver(): PageSaver
}

/** The menu of one page in a row of page previews: move, insert, duplicate, delete. */
@Composable
fun PageMenu(
    appRepository: AppRepository,
    notebookId: String? = null,
    pageId: String,
    index: Int? = null,
    canDelete: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSaver = remember(context) {
        EntryPoints.get(context.applicationContext, PageMenuEntryPoint::class.java).pageSaver()
    }
    val snackState = LocalSnackContext.current
    ScaledPopup(
        alignment = Alignment.TopStart,
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .widthIn(min = 220.dp)
                .width(IntrinsicSize.Max)
                .nSurface(NotableTheme.colors)
                .padding(vertical = NotableSpace.xs + NotableSpace.xxs)
        ) {
            if (notebookId != null && index != null) {
                NMenuItem(stringResource(R.string.page_menu_move_left), icon = FeatherIcons.ArrowLeft, onClick = {
                    scope.launch(Dispatchers.IO) {
                        appRepository.edits.movePage(notebookId, pageId, index - 1)
                    }
                })
                NMenuItem(stringResource(R.string.page_menu_move_right), icon = FeatherIcons.ArrowRight, onClick = {
                    scope.launch(Dispatchers.IO) {
                        appRepository.edits.movePage(notebookId, pageId, index + 1)
                    }
                })
                NMenuItem(stringResource(R.string.page_menu_insert_after), icon = FeatherIcons.FilePlus, onClick = {
                    scope.launch(Dispatchers.IO) {
                        appRepository.newPageInBook(notebookId, index + 1)
                    }
                })
            }

            NMenuItem(stringResource(R.string.page_menu_duplicate), icon = FeatherIcons.Copy, onClick = {
                scope.launch(Dispatchers.IO) {
                    // The copy is read from the database: write the page's pending edits first.
                    pageSaver.saveAll().onSuccess { appRepository.duplicatePage(pageId) }
                }
            })
            NMenuItem(stringResource(R.string.copy_link), icon = FeatherIcons.Link, onClick = {
                scope.launch {
                    val title = pageLinkTitle(appRepository, pageId)
                    copyLink(context, DeepLink.Page(pageId), title, snackState::showOrUpdateSnack)
                    onClose()
                }
            })
            if (canDelete) {
                NMenuDivider()
                NMenuItem(stringResource(R.string.page_menu_delete), icon = FeatherIcons.Trash2, onClick = {
                    scope.launch {
                        deletePage(appRepository, pageId, context.filesDir)
                    }
                })
            }
        }
    }
}
