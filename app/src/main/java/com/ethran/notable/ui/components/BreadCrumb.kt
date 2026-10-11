package com.ethran.notable.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.InkaTheme

@Composable
fun BreadCrumb(
    modifier: Modifier = Modifier,
    folders: List<Folder> = emptyList(),
    fontSize: Int = 20,
    onSelectFolderId: (String?) -> Unit
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.home_view_name),
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Serif,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .noRippleClickable { onSelectFolderId(null) }
                .padding(vertical = 10.dp, horizontal = 2.dp))

        folders.forEach { f ->
            Icon(
                imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = Ink,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = f.title,
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Serif,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.noRippleClickable { onSelectFolderId(f.id) }
                    .padding(vertical = 10.dp, horizontal = 2.dp))
        }
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
