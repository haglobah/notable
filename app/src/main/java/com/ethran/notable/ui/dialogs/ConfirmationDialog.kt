package com.ethran.notable.ui.dialogs

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportOptions
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NotableTheme
import kotlinx.coroutines.launch

/**
 * A question with a message, Cancel and one confirming verb. Pass a verb as [confirmButtonText]
 * ("Delete", not "Confirm") and [destructive] for deletes.
 */
@Composable
fun ShowSimpleConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmButtonText: String = stringResource(R.string.dialog_confirm),
    cancelButtonText: String = stringResource(R.string.dialog_cancel),
    destructive: Boolean = false,
) {
    ShowConfirmationDialog(
        title = title,
        content = { Text(text = message, style = NotableTheme.type.body, color = NotableTheme.colors.ink) },
        onConfirm = onConfirm,
        onCancel = onCancel,
        onDismiss = onCancel,
        confirmButtonText = confirmButtonText,
        cancelButtonText = cancelButtonText,
        destructive = destructive,
    )
}


@Composable
fun ShowConfirmationDialog(
    title: String,
    content: @Composable (() -> Unit),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit = onCancel,
    confirmButtonText: String = stringResource(R.string.dialog_confirm),
    cancelButtonText: String = stringResource(R.string.dialog_cancel),
    destructive: Boolean = false,
) {
    NDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        actions = {
            NButton(cancelButtonText, onCancel, style = NButtonStyle.Quiet)
            NButton(confirmButtonText, onConfirm, style = if (destructive) NButtonStyle.Danger else NButtonStyle.Primary)
        },
    ) {
        content()
    }
}

@Composable
fun ShowExportDialog(
    exportEngine: ExportEngine,
    snackManager: SnackState,
    bookId: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val exportingPdf = stringResource(R.string.exporting_the_book_to, "PDF")
    val exportingXopp = stringResource(R.string.exporting_the_book_to, "Xopp")
    NDialog(
        title = stringResource(R.string.export_heading),
        onDismiss = onCancel,
        maxWidth = 480.dp,
        actions = {
            NButton(stringResource(R.string.dialog_cancel), onCancel, style = NButtonStyle.Quiet)
            NButton("Xopp", onClick = {
                scope.launch {
                    snackManager.runWithSnack(exportingXopp) {
                        exportEngine.export(
                            target = ExportTarget.Book(bookId = bookId),
                            format = ExportFormat.XOPP,
                        )
                    }
                }
                onConfirm()
            }, style = NButtonStyle.Secondary)
            NButton(stringResource(R.string.bg_mode_pdf), onClick = {
                scope.launch {
                    snackManager.runWithSnack(exportingPdf) {
                        exportEngine.export(
                            target = ExportTarget.Book(bookId = bookId),
                            format = ExportFormat.PDF,
                            options = ExportOptions(
                                copyToClipboard = false,
                            )
                        )
                    }
                }
                onConfirm()
            }, style = NButtonStyle.Primary)
        },
    ) {
        Text(
            stringResource(R.string.export_message),
            style = NotableTheme.type.body,
            color = NotableTheme.colors.secondary,
        )
    }
}
