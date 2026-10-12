package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.PaperLine
import com.ethran.notable.ui.theme.SecondaryInk

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookCard(
    modifier: Modifier = Modifier,
    bookId: String,
    title: String,
    pageIds: List<String>,
    openPageId: String?,
    onOpen: (bookId: String, pageId: String) -> Unit,
    onOpenSettings: (bookId: String) -> Unit,
    onPreviewMissing: (String) -> Unit = {},
) {

    Column(modifier = modifier.fillMaxWidth().combinedClickable(
        onClick = { onOpen(bookId, openPageId ?: pageIds.first()) },
        onLongClick = { onOpenSettings(bookId) }
    )) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.White)
                .border(1.dp, PaperLine)
        ) {
            PagePreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                pageId = pageIds[0],
                onPreviewMissing = onPreviewMissing
            )
            // Keep the paper edge visible above the loaded preview.
            Box(Modifier.matchParentSize().border(1.dp, PaperLine))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle2,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(R.plurals.notebook_page_count, pageIds.size, pageIds.size),
                    style = MaterialTheme.typography.caption,
                    color = SecondaryInk,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            PaperIconAction(
                description = stringResource(R.string.library_notebook_details, title),
                icon = Icons.Default.MoreHoriz,
                onClick = { onOpenSettings(bookId) }
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 196, heightDp = 320)
@Composable
private fun NotebookCardPreview() {
    InkaTheme(darkTheme = false, scaleFactor = 1f) {
        NotebookCard(
            modifier = Modifier.padding(16.dp),
            bookId = "preview",
            title = "Ideas for a quieter day",
            pageIds = listOf("page-1", "page-2", "page-3"),
            openPageId = "page-1",
            onOpen = { _, _ -> },
            onOpenSettings = {}
        )
    }
}
