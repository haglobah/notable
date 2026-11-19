package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Bookmark
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.CornerUpRight
import compose.icons.feathericons.Info
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X

/**
 * The library bar while selecting (docs/plans/library-features.md): it takes the search bar's
 * place. First row: close, how many, Select all. Second row: what can be done with them. Book
 * actions name the change they make: "Mark as read" while any selected book isn't finished, else
 * "Mark as unread"; the same for Read next. Folders are moved and trashed with the books.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibrarySelectionBar(
    count: Int,
    /** The selected notebooks (folders aren't books). */
    books: List<Notebook>,
    canSelectMore: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onMove: () -> Unit,
    onSetFinished: (Boolean) -> Unit,
    onSetQueued: (Boolean) -> Unit,
    onTrash: () -> Unit,
    /** Shown with exactly one item selected: its details dialog. */
    onDetails: (() -> Unit)?,
) {
    val c = NotableTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
            NIconButton(FeatherIcons.X, stringResource(R.string.select_done), onClear, outlined = true)
            Text(pluralStringResource(R.plurals.select_count, count, count), style = NotableTheme.type.heading,
                color = c.ink, modifier = Modifier.weight(1f))
            if (canSelectMore) NButton(stringResource(R.string.select_all), onSelectAll, style = NButtonStyle.Quiet,
                icon = FeatherIcons.CheckSquare)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
            verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
            NButton(stringResource(R.string.select_move), onMove, icon = FeatherIcons.CornerUpRight)
            if (books.isNotEmpty()) {
                val allRead = books.all { it.finishedAt != null }
                NButton(stringResource(if (allRead) R.string.select_mark_unread else R.string.select_mark_read),
                    { onSetFinished(!allRead) }, icon = FeatherIcons.BookOpen)
                val allQueued = books.all { it.queuedAt != null }
                NButton(stringResource(if (allQueued) R.string.select_unqueue else R.string.select_queue),
                    { onSetQueued(!allQueued) }, icon = FeatherIcons.Bookmark)
            }
            if (onDetails != null) NButton(stringResource(R.string.select_details), onDetails, icon = FeatherIcons.Info)
            NButton(stringResource(R.string.select_trash), onTrash, style = NButtonStyle.Danger, icon = FeatherIcons.Trash2)
        }
    }
}
