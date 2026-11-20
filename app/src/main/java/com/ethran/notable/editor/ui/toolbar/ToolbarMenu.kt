package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.ui.components.RatingPicker
import com.ethran.notable.ui.components.ScaledPopup
import com.ethran.notable.ui.design.NMenuDivider
import com.ethran.notable.ui.design.NMenuHeader
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.nSurface
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Circle
import compose.icons.feathericons.Flag
import compose.icons.feathericons.Home
import compose.icons.feathericons.Layout
import compose.icons.feathericons.List
import compose.icons.feathericons.Maximize
import compose.icons.feathericons.Search
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Star
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2

/**
 * Menu for the toolbar, providing export options and other page-level actions.
 * Centralizes actions via [ToolbarAction].
 */
@Composable
fun ToolbarMenu(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val density = LocalDensity.current

    ScaledPopup(
        alignment = Alignment.TopEnd,
        onDismissRequest = {
            onAction(ToolbarAction.ToggleMenu)
        },
        offset = with(density) {
            IntOffset(
                (-10).dp.roundToPx(),
                (BUTTON_SIZE + 6).dp.roundToPx()
            )
        },
        properties = PopupProperties(focusable = true),
    ) {
        ToolbarMenuContent(
            uiState = uiState,
            onAction = onAction
        )
    }
}

@Composable
private fun ToolbarMenuContent(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit
) {
    // Runs the action and closes the menu, as every row except the rating toggle does.
    fun run(action: ToolbarAction) {
        onAction(action)
        onAction(ToolbarAction.ToggleMenu)
    }

    // Long menus (a book with commands) scroll instead of running off the screen.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f - BUTTON_SIZE - 5 - 50).dp
    Column(
        Modifier
            // Room for a bottom toolbar: the popup window is pushed up above it.
            .padding(bottom = (BUTTON_SIZE + 5).dp)
            .widthIn(min = 260.dp, max = 420.dp)
            .width(IntrinsicSize.Max)
            .heightIn(max = maxHeight)
            .nSurface(NotableTheme.colors)
            .verticalScroll(rememberScrollState())
            .padding(vertical = NotableSpace.xs + NotableSpace.xxs)
    ) {
        NMenuHeader(stringResource(R.string.menu_group_navigate))
        NMenuItem(stringResource(R.string.home_view_name), icon = FeatherIcons.Home, onClick = {
            run(ToolbarAction.NavigateToLibrary)
        })

        // Reading a notebook made from a PDF or EPUB (docs/plans/ebook-features.md)
        if (uiState.isBook) {
            NMenuDivider()
            NMenuHeader(stringResource(R.string.menu_group_reading))
            NMenuItem(stringResource(R.string.book_contents), icon = FeatherIcons.List, onClick = {
                onAction(ToolbarAction.OpenContents)
            })
            NMenuItem(stringResource(R.string.book_search), icon = FeatherIcons.Search, onClick = {
                onAction(ToolbarAction.OpenBookSearch)
            })
            NMenuItem(
                stringResource(if (uiState.isFinished) R.string.book_mark_unread else R.string.book_mark_finished),
                icon = if (uiState.isFinished) FeatherIcons.Circle else FeatherIcons.CheckCircle,
                onClick = { run(ToolbarAction.SetFinished(!uiState.isFinished)) },
            )
            // The picker opens in place, inside the menu: no hand travel to a dialog.
            var rating by remember { mutableStateOf(false) }
            NMenuItem(
                stringResource(R.string.rating_menu),
                icon = FeatherIcons.Star,
                meta = uiState.rating?.let { stringResource(R.string.rating_value, it) },
                onClick = { rating = !rating },
            )
            if (rating) RatingPicker(uiState.rating, onRate = { onAction(ToolbarAction.SetRating(it)) },
                modifier = Modifier.padding(horizontal = NotableSpace.lg, vertical = NotableSpace.sm))
        }

        // Every share and export choice sits behind one row (ShareDialog).
        NMenuDivider()
        NMenuItem(stringResource(R.string.menu_share), icon = FeatherIcons.Share2, onClick = {
            run(ToolbarAction.ToggleShareDialog(true))
        })
        // One view for every page of the notebook (FixedView), as reMarkable's "Adjust view".
        if (uiState.notebookId != null) {
            NMenuItem(
                stringResource(R.string.menu_view),
                icon = FeatherIcons.Maximize,
                meta = uiState.fixedView?.let { stringResource(fitLabel(it.fit)) },
                onClick = { run(ToolbarAction.ToggleViewDialog(true)) },
            )
        }

        NMenuDivider()
        NMenuHeader(stringResource(R.string.menu_group_edit))
        NMenuItem(stringResource(R.string.clean_all_strokes), icon = FeatherIcons.Trash2, onClick = {
            run(ToolbarAction.ClearAllStrokes)
        })
        NMenuItem(stringResource(R.string.change_background), icon = FeatherIcons.Layout, onClick = {
            run(ToolbarAction.ToggleBackgroundSelector(true))
        })

        NMenuDivider()
        NMenuHeader(stringResource(R.string.menu_group_help))
        NMenuItem(stringResource(R.string.bug_report), icon = FeatherIcons.Flag, onClick = {
            run(ToolbarAction.NavigateToBugReport)
        })

        // Commands of the live code image and their built-ins (ADR-002).
        if (uiState.commands.isNotEmpty()) {
            NMenuDivider()
            NMenuHeader(stringResource(R.string.menu_group_commands))
            uiState.commands.forEach { command ->
                NMenuItem(command.title, icon = FeatherIcons.Terminal, onClick = {
                    run(ToolbarAction.RunCommand(command.id))
                })
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
fun ToolbarMenuPreview() {
    ToolbarMenuContent(
        uiState = ToolbarUiState(
            isMenuOpen = true,
            notebookId = "book1",
            mode = Mode.Draw,
            pen = Pen.BALLPEN,
            penSettings = ToolbarPen.defaultPenSettings
        ),
        onAction = {}
    )
}
