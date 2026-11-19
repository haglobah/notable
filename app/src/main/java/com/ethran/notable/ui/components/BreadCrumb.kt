package com.ethran.notable.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.ethran.notable.ui.theme.InkaTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight

/**
 * Where you are: Library › Folder › Folder. Ancestors are secondary, the current place is ink;
 * every crumb is a 44 dp target. [fontSize] picks the type role (kept for callers): 22 and up is
 * the screen title, 18 and up a heading, smaller is body text (e.g. "Location" in a dialog).
 */
@Composable
fun BreadCrumb(
    modifier: Modifier = Modifier,
    folders: List<Folder> = emptyList(),
    fontSize: Int = 20,
    onSelectFolderId: (String?) -> Unit
) {
    val t = NotableTheme.type
    val style = when {
        fontSize >= 22 -> t.title
        fontSize >= 18 -> t.heading
        else -> t.body
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.xs)
    ) {
        Crumb(stringResource(R.string.home_view_name), style, current = folders.isEmpty()) { onSelectFolderId(null) }
        folders.forEachIndexed { i, f ->
            NIcon(FeatherIcons.ChevronRight, tint = NotableTheme.colors.secondary)
            Crumb(f.title, style, current = i == folders.lastIndex) { onSelectFolderId(f.id) }
        }
    }
}

@Composable
private fun Crumb(text: String, style: TextStyle, current: Boolean, onClick: () -> Unit) {
    val c = NotableTheme.colors
    Box(
        Modifier.heightIn(min = NotableSize.control).inkClickable(onClick = onClick).padding(horizontal = NotableSpace.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = if (current) c.ink else c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// TODO: Move it!!! And check the usage of it!!
suspend fun getFolderList(appRepository: AppRepository, folderId: String?): List<Folder> {
    val folderList = mutableListOf<Folder>()
    var currentId = folderId
    while (currentId != null) {
        val folder = appRepository.folderRepository.get(currentId)
        if (folder != null) {
            folderList.add(folder)
            currentId = folder.parentFolderId
        } else {
            currentId = null
        }
    }
    folderList.reverse()
    return folderList
}

suspend fun getFolderList(appRepository: AppRepository, page: Page?): List<Folder> {
    val folderList = mutableListOf<Folder>()
    var currentFolderId = page?.parentFolderId
    while (currentFolderId != null) {
        val folder = appRepository.folderRepository.get(currentFolderId)
        if (folder != null) {
            folderList.add(folder)
            currentFolderId = folder.parentFolderId
        } else {
            currentFolderId = null
        }
    }
    folderList.reverse() // Parents first
    return folderList
}

@Preview(showBackground = true)
@Composable
fun BreadCrumbPreview() {
    InkaTheme {
        BreadCrumb(
            folders = listOf(
                Folder(id = "folder1", title = "Folder 1"),
                Folder(id = "folder2", title = "Folder 2"),
            ), fontSize = 20, onSelectFolderId = {})
    }
}
