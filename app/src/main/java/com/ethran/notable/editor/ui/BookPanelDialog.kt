package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.R
import com.ethran.notable.editor.BookPanel
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.components.PaperAction
import com.ethran.notable.ui.components.ScaledDialog
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.Paper
import com.ethran.notable.ui.theme.PaperInset
import com.ethran.notable.ui.theme.PaperLine
import com.ethran.notable.ui.theme.SecondaryInk

/**
 * The contents or the search of the book a notebook was made from (docs/plans/ebook-features.md).
 * Flat and high contrast for e-ink: no animations, full-width rows of at least 48 dp.
 */
@Composable
fun BookPanelDialog(
    panel: BookPanel,
    onSearch: (String) -> Unit,
    onJump: (pageId: String) -> Unit,
    onClose: () -> Unit,
) {
    ScaledDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 900.dp)
                .background(Paper).border(1.dp, Ink).padding(24.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (panel is BookPanel.Contents) R.string.book_contents else R.string.book_search),
                    style = MaterialTheme.typography.h5, color = Ink, modifier = Modifier.weight(1f)
                )
                PaperAction(stringResource(R.string.book_close), onClick = onClose)
            }
            Spacer(Modifier.height(16.dp))
            when (panel) {
                is BookPanel.Contents -> Contents(panel, onJump)
                is BookPanel.Search -> Search(panel, onSearch, onJump)
            }
        }
    }
}

@Composable
private fun Contents(panel: BookPanel.Contents, onJump: (String) -> Unit) {
    when {
        panel.loading -> Note(stringResource(R.string.book_loading))
        panel.error != null -> Note(stringResource(R.string.book_failed, panel.error))
        panel.lines.isEmpty() && panel.missing == 0 -> Note(stringResource(R.string.book_contents_empty))
        else -> {
            Missing(panel.missing)
            LazyColumn(Modifier.fillMaxWidth().autoEInkAnimationOnScroll()) {
                items(panel.lines) { line ->
                    Line(onClick = { onJump(line.pageId) }, indent = line.depth) {
                        Text(line.title, style = MaterialTheme.typography.body1, color = Ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${line.pageIndex + 1}", style = MaterialTheme.typography.body2, color = SecondaryInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun Search(panel: BookPanel.Search, onSearch: (String) -> Unit, onJump: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(panel.query) }
    val hint = stringResource(R.string.book_search_hint)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (panel.results == null && !panel.running) runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = MaterialTheme.typography.body1.copy(color = Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.body1, color = SecondaryInk)
                inner()
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = hint }.focusRequester(focus)
                .background(PaperInset).border(1.dp, PaperLine).padding(horizontal = 12.dp, vertical = 14.dp)
        )
        PaperAction(stringResource(R.string.book_search_go), onClick = { onSearch(query) }, primary = true,
            enabled = query.isNotBlank())
    }
    Spacer(Modifier.height(16.dp))
    val results = panel.results
    when {
        panel.running -> Note(if (panel.total > 0) stringResource(R.string.book_searching, panel.done, panel.total)
            else stringResource(R.string.book_loading))
        panel.error != null -> Note(stringResource(R.string.book_failed, panel.error))
        results == null -> Unit
        results.isEmpty() && panel.missing == 0 -> Note(stringResource(R.string.book_search_none))
        else -> {
            Note(pluralStringResource(R.plurals.book_search_results, results.size, results.size))
            Missing(panel.missing)
            LazyColumn(Modifier.fillMaxWidth().autoEInkAnimationOnScroll()) {
                items(results) { r ->
                    Line(onClick = { onJump(r.pageId) }) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.book_page, r.pageIndex + 1), style = MaterialTheme.typography.subtitle2, color = Ink)
                            Text(r.snippet, style = MaterialTheme.typography.body2, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.body2, color = SecondaryInk, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Missing(count: Int) {
    if (count > 0) Note(pluralStringResource(R.plurals.book_missing_pages, count, count))
}

@Composable
private fun Line(onClick: () -> Unit, indent: Int = 0, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClick = onClick)
                .padding(start = (indent.coerceAtMost(6) * 24).dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Divider(color = PaperLine)
    }
}
