package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CornerLeftUp
import compose.icons.feathericons.Folder


@Composable
fun ShowFolderSelectionDialog(
    appRepository: AppRepository,
    notebookName: String,
    initialFolderId: String?,
    onCancel: () -> Unit,
    /** Folders that can't be a target: the ones being moved (a folder can't go into itself). */
    excludeIds: Set<String> = emptySet(),
    /** Instead of "Move “name”", e.g. "Move 3 items". */
    title: String? = null,
    onConfirm: (String?) -> Unit
) {

    var currentFolderId by remember { mutableStateOf(initialFolderId) }
    val availableFolders by appRepository.folderRepository.getAllInFolder(currentFolderId)
        .observeAsState()

    var breadcrumbFolders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    LaunchedEffect(currentFolderId) {
        breadcrumbFolders = getFolderList(appRepository, currentFolderId)
    }

    val parentFolder = appRepository.folderRepository.getParentLive(currentFolderId).observeAsState().value
    val c = NotableTheme.colors

    NDialog(
        title = title ?: stringResource(R.string.move_heading, notebookName),
        onDismiss = onCancel,
        maxWidth = 560.dp,
        actions = {
            NButton(stringResource(R.string.dialog_cancel), onCancel, style = NButtonStyle.Quiet)
            NButton(stringResource(R.string.move_here), { onConfirm(currentFolderId) }, style = NButtonStyle.Primary)
        },
    ) {
        Text(stringResource(R.string.move_hint), style = NotableTheme.type.meta, color = c.secondary)
        Spacer(Modifier.height(NotableSpace.md))
        PathBar(breadcrumbFolders) { currentFolderId = it }
        NDivider()
        Column(Modifier.fillMaxWidth()) {
            NMenuItem(
                stringResource(R.string.move_up),
                onClick = { currentFolderId = parentFolder },
                icon = FeatherIcons.CornerLeftUp,
                enabled = currentFolderId != null,
            )
            // Folders in the trash, and those being moved, aren't places to move to.
            val folders = availableFolders.orEmpty().filter { it.trashedAt == null && it.id !in excludeIds }
            folders.forEach { folder ->
                NMenuItem(
                    folder.title,
                    onClick = { currentFolderId = folder.id }, // Navigate into child folder
                    icon = FeatherIcons.Folder,
                )
            }
            if (availableFolders != null && folders.isEmpty()) {
                Text(
                    stringResource(R.string.move_no_folders),
                    style = NotableTheme.type.meta, color = c.secondary,
                    modifier = Modifier.padding(horizontal = NotableSpace.lg, vertical = NotableSpace.md),
                )
            }
        }
        NDivider()
    }
}

/** Where the notebook would go: Library › Folder › Folder, each step tappable. Sans, not serif. */
@Composable
private fun PathBar(folders: List<Folder>, onSelect: (String?) -> Unit) {
    val c = NotableTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.xs),
    ) {
        val steps = listOf<Pair<String?, String>>(null to stringResource(R.string.home_view_name)) +
            folders.map { it.id to it.title }
        steps.forEachIndexed { i, (id, title) ->
            if (i > 0) NIcon(FeatherIcons.ChevronRight, tint = c.secondary, size = 16.dp)
            val last = i == steps.lastIndex
            Text(
                title,
                style = if (last) NotableTheme.type.label else NotableTheme.type.body,
                color = if (last) c.ink else c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.inkClickable { onSelect(id) }
                    .padding(horizontal = NotableSpace.xs, vertical = NotableSpace.md),
            )
        }
    }
}
