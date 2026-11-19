package com.ethran.notable.ui.dialogs

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.db.Notebook

@Composable
fun EmptyBookWarningHandler(
    emptyBook: Notebook, onDelete: (String) -> Unit, onDismiss: () -> Unit
) {
    val created = DateFormat.format("dd MMM yyyy", emptyBook.createdAt).toString()
    ShowSimpleConfirmationDialog(
        title = stringResource(R.string.empty_book_heading),
        message = stringResource(R.string.empty_book_message, emptyBook.title, created),
        confirmButtonText = stringResource(R.string.dialog_delete),
        cancelButtonText = stringResource(R.string.empty_book_keep),
        destructive = true,
        onConfirm = {
            onDelete(emptyBook.id)
        },
        onCancel = { onDismiss() })
}
