package com.ethran.notable.editor.ui.toolbar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.FixedView
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NMenuDivider
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NSegmented
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.Share2

/** The label of a fixed view's fit, for the menu row and the view dialog. */
@StringRes
fun fitLabel(fit: FixedView.Fit): Int = when (fit) {
    FixedView.Fit.Width -> R.string.view_fit_width
    FixedView.Fit.Page -> R.string.view_fit_page
    FixedView.Fit.Custom -> R.string.view_custom
}

/**
 * Every way to share or export, behind the menu's one "Share and export" row: what to send (this
 * page or the whole notebook), then how. A choice runs and closes the dialog.
 */
@Composable
fun ShareDialog(uiState: ToolbarUiState, onAction: (ToolbarAction) -> Unit) {
    val hasNotebook = uiState.notebookId != null
    var wholeNotebook by remember { mutableStateOf(false) }
    val close = { onAction(ToolbarAction.ToggleShareDialog(false)) }
    fun run(action: ToolbarAction) {
        close()
        onAction(action)
    }
    fun export(format: ExportFormat) =
        run(if (wholeNotebook) ToolbarAction.ExportBook(format) else ToolbarAction.ExportPage(format))

    NDialog(title = stringResource(R.string.share_title), onDismiss = close, maxWidth = 460.dp) {
        if (hasNotebook) {
            NSegmented(
                options = listOf(
                    false to stringResource(R.string.share_scope_page),
                    true to stringResource(R.string.share_scope_notebook),
                ),
                selected = wholeNotebook,
                onSelect = { wholeNotebook = it },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(NotableSpace.md))
        }
        NMenuItem(stringResource(R.string.share_as_pdf), icon = FeatherIcons.Share2, onClick = {
            run(ToolbarAction.SharePdf(book = wholeNotebook))
        })
        NMenuDivider()
        NMenuItem(stringResource(R.string.export_as, "PDF"), icon = FeatherIcons.Download, onClick = {
            export(ExportFormat.PDF)
        })
        NMenuItem(stringResource(R.string.export_as, "PNG"), icon = FeatherIcons.Download, onClick = {
            export(ExportFormat.PNG)
        })
        // A notebook has no single JPEG.
        if (!wholeNotebook) NMenuItem(stringResource(R.string.export_as, "JPEG"), icon = FeatherIcons.Download, onClick = {
            export(ExportFormat.JPEG)
        })
        NMenuItem(stringResource(R.string.export_as, "xopp"), icon = FeatherIcons.Download, onClick = {
            export(ExportFormat.XOPP)
        })
    }
}

/**
 * The notebook's view, as reMarkable's "Adjust view": free zoom per page, or one view for every
 * page — fit to width, fit to page, or a custom one (pinch and pan, then Set).
 */
@Composable
fun ViewDialog(uiState: ToolbarUiState, onAction: (ToolbarAction) -> Unit) {
    val current = uiState.fixedView?.fit
    NDialog(
        title = stringResource(R.string.view_title),
        onDismiss = { onAction(ToolbarAction.ToggleViewDialog(false)) },
        maxWidth = 460.dp,
    ) {
        Text(
            stringResource(R.string.view_help),
            style = NotableTheme.type.meta,
            color = NotableTheme.colors.secondary,
            modifier = Modifier.padding(bottom = NotableSpace.sm),
        )
        NMenuItem(
            stringResource(R.string.view_free), checked = current == null,
            meta = stringResource(R.string.view_free_meta),
            onClick = { onAction(ToolbarAction.ChooseFixedView(null)) },
        )
        listOf(FixedView.Fit.Width, FixedView.Fit.Page, FixedView.Fit.Custom).forEach { fit ->
            NMenuItem(
                stringResource(fitLabel(fit)), checked = current == fit,
                meta = if (fit == FixedView.Fit.Custom) stringResource(R.string.view_custom_meta) else null,
                onClick = { onAction(ToolbarAction.ChooseFixedView(fit)) },
            )
        }
    }
}

/**
 * While a custom view is chosen: a bar at the far side from the toolbar saying what to do, with
 * Cancel and Set. The page stays free to pinch and pan around it.
 */
@Composable
fun CustomViewBar(onAction: (ToolbarAction) -> Unit) {
    val c = NotableTheme.colors
    val toolbarAtTop = GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (toolbarAtTop) Alignment.BottomCenter else Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .padding(NotableSpace.xl)
                .background(c.paper, NotableShape.surface)
                .border(NotableSize.hairline, c.ink, NotableShape.surface)
                .padding(horizontal = NotableSpace.lg, vertical = NotableSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.md),
        ) {
            Text(stringResource(R.string.view_custom_hint), style = NotableTheme.type.body, color = c.ink)
            NButton(stringResource(R.string.cancel_view), onClick = { onAction(ToolbarAction.CancelCustomView) })
            NButton(
                stringResource(R.string.view_set),
                onClick = { onAction(ToolbarAction.SetCustomView) },
                style = NButtonStyle.Primary,
            )
        }
    }
}
