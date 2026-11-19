package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.theme.InkaTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.MoreHorizontal

/**
 * A notebook in the library: its cover as paper (square, a hairline edge), the title (two lines at
 * most), meta lines in the meta role, and a quiet details button.
 */
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
    /** The book's authors, from its metadata; shown under the title. */
    authors: List<String> = emptyList(),
    /** A book shows where the reader is ("Page 3 of 120") instead of its page count. */
    isBook: Boolean = false,
    finished: Boolean = false,
    /** The folder it is in, for search results that come from every folder. */
    folderPath: String? = null,
    /** 1 to 10, shown as "★ 8". */
    rating: Int? = null,
) {
    val c = NotableTheme.colors
    val t = NotableTheme.type
    Column(modifier = modifier.fillMaxWidth().combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = { onOpen(bookId, openPageId ?: pageIds.first()) },
        onLongClick = { onOpenSettings(bookId) }
    )) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(c.paper, NotableShape.paper)
        ) {
            PagePreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                pageId = pageIds[0],
                onPreviewMissing = onPreviewMissing
            )
            // Keep the paper edge visible above the loaded preview.
            Box(Modifier.matchParentSize().border(NotableSize.hairline, c.rule, NotableShape.paper))
        }
        Row(Modifier.fillMaxWidth().padding(top = NotableSpace.sm), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = NotableSpace.xs)) {
                val shown = breakable(title)
                Text(
                    text = shown,
                    style = t.label,
                    color = c.ink,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                    // Tests and accessibility read the title as stored, without break hints.
                    modifier = if (shown == title) Modifier else Modifier.clearAndSetSemantics { text = AnnotatedString(title) },
                )
                if (authors.isNotEmpty()) Meta(authors.joinToString(", "), Modifier.padding(top = NotableSpace.xxs))
                val openIndex = openPageId?.let { pageIds.indexOf(it) } ?: -1
                Meta(
                    if (isBook && openIndex >= 0) stringResource(R.string.library_progress, openIndex + 1, pageIds.size)
                    else pluralStringResource(R.plurals.notebook_page_count, pageIds.size, pageIds.size),
                    Modifier.padding(top = NotableSpace.xxs),
                )
                // Status, one quiet line: "★ 8 · Finished".
                val status = listOfNotNull(
                    rating?.let { stringResource(R.string.rating_value, it) },
                    if (finished) stringResource(R.string.library_finished) else null,
                )
                if (status.isNotEmpty()) Meta(status.joinToString(" · "), Modifier.padding(top = NotableSpace.xxs))
                if (!folderPath.isNullOrEmpty()) Meta(folderPath, Modifier.padding(top = NotableSpace.xxs))
            }
            NIconButton(
                icon = FeatherIcons.MoreHorizontal,
                contentDescription = stringResource(R.string.library_notebook_details, title),
                onClick = { onOpenSettings(bookId) },
                size = NControlSize.Compact,
            )
        }
    }
}

@Composable
private fun Meta(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NotableTheme.type.meta, color = NotableTheme.colors.secondary, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Lets a long file-like title ("Manifold_for_Charity_Inc_S") wrap after its separators instead of
 * mid-word: a zero-width space after each `_`, `.`, `/` and `-`.
 */
private fun breakable(title: String): String =
    if (title.none { it == '_' || it == '.' || it == '/' || it == '-' }) title
    else buildString { title.forEach { ch -> append(ch); if (ch == '_' || ch == '.' || ch == '/' || ch == '-') append('\u200B') } }

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
