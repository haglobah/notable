package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.sync.SyncBadge
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
    syncBadge: SyncBadge? = null,
    onOpen: (bookId: String, pageId: String) -> Unit,
    onOpenSettings: (bookId: String) -> Unit,
    onPreviewMissing: (String) -> Unit = {},
) {

    Column(modifier = modifier.fillMaxWidth()) {
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
                    .aspectRatio(3f / 4f)
                    .combinedClickable(
                        onClick = { onOpen(bookId, openPageId ?: pageIds[0]) },
                        onLongClick = { onOpenSettings(bookId) }),
                pageId = pageIds[0],
                onPreviewMissing = onPreviewMissing
            )
            // Keep the paper edge visible above the loaded preview.
            Box(Modifier.matchParentSize().border(1.dp, PaperLine))
            syncBadge?.iconOrNull()?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = "Sync status: ${syncBadge.name}",
                    tint = Ink,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.White, CircleShape)
                        .padding(4.dp)
                        .size(18.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.subtitle2,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = pluralStringResource(
                R.plurals.notebook_page_count, pageIds.size, pageIds.size
            ),
            style = MaterialTheme.typography.caption,
            color = SecondaryInk,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Icon for the corner badge, or null when there is nothing worth showing. */
private fun SyncBadge.iconOrNull(): ImageVector? = when (this) {
    SyncBadge.SYNCED -> Icons.Default.CloudDone
    SyncBadge.NOT_SYNCED -> Icons.Default.CloudOff
    SyncBadge.SCHEDULED -> Icons.Default.Schedule
    SyncBadge.SYNCING -> Icons.Default.Sync
    SyncBadge.REMOTE_AHEAD -> Icons.Default.CloudDownload
    SyncBadge.CONFLICT -> Icons.Default.SyncProblem
    SyncBadge.ERROR -> Icons.Default.ErrorOutline
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
            syncBadge = SyncBadge.SYNCED,
            onOpen = { _, _ -> },
            onOpenSettings = {}
        )
    }
}
