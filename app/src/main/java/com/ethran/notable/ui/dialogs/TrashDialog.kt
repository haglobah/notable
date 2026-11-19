package com.ethran.notable.ui.dialogs

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NConfirmDialog
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NEmptyState
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.viewmodels.TrashRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.Folder

/**
 * The trash (docs/plans/library-features.md): what was moved there, newest first, each with when
 * and how long it has left; Restore puts it back, Delete removes it for good. Everything goes by
 * itself after [LibrarySearch.TRASH_DAYS] days.
 */
@Composable
fun TrashDialog(
    rows: List<TrashRow>,
    onRestore: (TrashRow) -> Unit,
    onDelete: (TrashRow) -> Unit,
    onEmpty: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashRow?>(null) }
    if (confirmEmpty) {
        NConfirmDialog(
            title = stringResource(R.string.trash_empty_title),
            message = stringResource(R.string.trash_empty_message),
            confirmText = stringResource(R.string.trash_empty),
            cancelText = stringResource(R.string.dialog_cancel),
            destructive = true,
            onConfirm = { confirmEmpty = false; onEmpty() },
            onDismiss = { confirmEmpty = false },
        )
        return
    }
    confirmDelete?.let { row ->
        NConfirmDialog(
            title = stringResource(R.string.trash_delete_title),
            message = stringResource(R.string.trash_delete_message, row.title),
            confirmText = stringResource(R.string.dialog_delete),
            cancelText = stringResource(R.string.dialog_cancel),
            destructive = true,
            onConfirm = { confirmDelete = null; onDelete(row) },
            onDismiss = { confirmDelete = null },
        )
        return
    }
    val c = NotableTheme.colors
    NDialog(
        title = stringResource(R.string.trash_title),
        onDismiss = onClose,
        maxWidth = 640.dp,
        titleAction = { NButton(stringResource(R.string.details_done), onClose, style = NButtonStyle.Primary) },
        actions = if (rows.isEmpty()) null else ({
            NButton(stringResource(R.string.trash_empty), { confirmEmpty = true }, style = NButtonStyle.Danger)
        }),
    ) {
        Text(stringResource(R.string.trash_hint, LibrarySearch.TRASH_DAYS), style = NotableTheme.type.meta, color = c.secondary)
        if (rows.isEmpty()) {
            NEmptyState(stringResource(R.string.trash_none))
            return@NDialog
        }
        val now = System.currentTimeMillis()
        rows.forEach { row ->
            NDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = NotableSpace.sm), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
                NIcon(if (row.item is LibrarySearch.TrashItem.Folder) FeatherIcons.Folder else FeatherIcons.Book, tint = c.ink)
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = NotableTheme.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val left = LibrarySearch.TRASH_DAYS - ((now - row.trashedAt.time) / DateUtils.DAY_IN_MILLIS).toInt()
                    Text(
                        stringResource(R.string.trash_row_meta,
                            DateUtils.getRelativeTimeSpanString(row.trashedAt.time, now, DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                            left.coerceAtLeast(0)),
                        style = NotableTheme.type.meta, color = c.secondary, maxLines = 1,
                    )
                }
                NButton(stringResource(R.string.trash_restore), { onRestore(row) }, size = NControlSize.Compact)
                NButton(stringResource(R.string.dialog_delete), { confirmDelete = row }, size = NControlSize.Compact,
                    style = NButtonStyle.Quiet)
            }
        }
        NDivider()
    }
}
