package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.editor.BookPanel
import com.ethran.notable.editor.ReadingPace
import com.ethran.notable.editor.SearchHighlights
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Search

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
    /** Go to page n (1-based) of the notebook. */
    onGoToPage: (Int) -> Unit = {},
    onClearHighlights: () -> Unit = {},
) {
    NDialog(
        title = stringResource(if (panel is BookPanel.Contents) R.string.book_contents else R.string.book_search),
        onDismiss = onClose,
        modifier = Modifier.heightIn(max = 900.dp),
        maxWidth = 640.dp,
        // The lists below scroll by themselves.
        scrollable = false,
        titleAction = { NButton(stringResource(R.string.book_close), onClose, style = NButtonStyle.Secondary) },
    ) {
        when (panel) {
            is BookPanel.Contents -> {
                WhereAndGoTo(panel, onGoToPage)
                Contents(panel, onJump)
            }
            is BookPanel.Search -> Search(panel, onSearch, onJump, onClearHighlights)
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
                        Text(line.title, style = NotableTheme.type.body, color = NotableTheme.colors.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${line.pageIndex + 1}", style = NotableTheme.type.meta, color = NotableTheme.colors.secondary)
                    }
                }
            }
        }
    }
}

/**
 * Over the contents (docs/plans/library-features.md, 31 and 34): "Page 12 of 300 · about 4 h 05 min
 * left · 12 min in this chapter", and a page number field with Go.
 */
@Composable
private fun WhereAndGoTo(panel: BookPanel.Contents, onGoToPage: (Int) -> Unit) {
    if (panel.pageCount <= 0) return
    val pace = panel.secondsPerPage
    val parts = mutableListOf<String>()
    if (panel.current >= 0) parts += stringResource(R.string.book_page_of, panel.current + 1, panel.pageCount)
    if (pace != null && panel.current >= 0) {
        parts += stringResource(R.string.book_time_left, duration(ReadingPace.pagesLeft(panel.current, panel.pageCount) * pace))
        ReadingPace.chapterPagesLeft(panel.current, panel.lines.map { it.pageIndex })?.let { n ->
            parts += stringResource(R.string.book_chapter_left, duration(n * pace))
        }
    } else if (panel.current >= 0) parts += stringResource(R.string.book_time_unknown)
    Note(parts.joinToString(" · "))
    var number by rememberSaveable { mutableStateOf("") }
    val go = { number.toIntOrNull()?.let(onGoToPage); Unit }
    val label = stringResource(R.string.book_go_to_page)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotableSpace.md),
        modifier = Modifier.padding(bottom = NotableSpace.md)) {
        NTextField(
            value = number, onValueChange = { v -> number = v.filter(Char::isDigit).take(6) }, singleLine = true,
            placeholder = stringResource(R.string.book_go_to_page_hint, panel.pageCount),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        NButton(label, onClick = go, enabled = number.toIntOrNull()?.let { it in 1..panel.pageCount } == true)
    }
}

/** "4 h 05 min", "12 min", "under a minute". */
@Composable
private fun duration(seconds: Float): String {
    val m = ReadingPace.minutes(seconds)
    return when {
        m < 1 -> stringResource(R.string.book_duration_short)
        m < 60 -> stringResource(R.string.book_duration_m, m)
        else -> stringResource(R.string.book_duration_hm, m / 60, m % 60)
    }
}

@Composable
private fun Search(panel: BookPanel.Search, onSearch: (String) -> Unit, onJump: (String) -> Unit, onClearHighlights: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(panel.query) }
    val hint = stringResource(R.string.book_search_hint)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (panel.results == null && !panel.running) runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
        NTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = hint,
            leadingIcon = FeatherIcons.Search,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        NButton(stringResource(R.string.book_search_go), onClick = { onSearch(query) }, style = NButtonStyle.Primary,
            enabled = query.isNotBlank())
    }
    Spacer(Modifier.height(NotableSpace.lg))
    val results = panel.results
    when {
        panel.running -> Note(if (panel.total > 0) stringResource(R.string.book_searching, panel.done, panel.total)
            else stringResource(R.string.book_loading))
        panel.error != null -> Note(stringResource(R.string.book_failed, panel.error))
        results == null -> Unit
        results.isEmpty() && panel.missing == 0 -> Note(stringResource(R.string.book_search_none))
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Note(pluralStringResource(R.plurals.book_search_results, results.size, results.size)) }
                // Hits are drawn on their pages until the next search or this.
                if (!SearchHighlights.isEmpty) NButton(stringResource(R.string.book_clear_highlights), onClearHighlights,
                    style = NButtonStyle.Quiet, size = NControlSize.Compact)
            }
            Missing(panel.missing)
            LazyColumn(Modifier.fillMaxWidth().autoEInkAnimationOnScroll()) {
                items(results) { r ->
                    Line(onClick = { onJump(r.pageId) }) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.book_page, r.pageIndex + 1), style = NotableTheme.type.label, color = NotableTheme.colors.ink)
                            Text(r.snippet, style = NotableTheme.type.body, color = NotableTheme.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = NotableTheme.type.meta, color = NotableTheme.colors.secondary, modifier = Modifier.padding(vertical = NotableSpace.sm))
}

@Composable
private fun Missing(count: Int) {
    if (count > 0) Note(pluralStringResource(R.plurals.book_missing_pages, count, count))
}

@Composable
private fun Line(onClick: () -> Unit, indent: Int = 0, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NotableSize.menuRow)
                .inkClickable(onClick = onClick)
                .padding(start = NotableSpace.xl * indent.coerceAtMost(6), top = NotableSpace.md,
                    bottom = NotableSpace.md, end = NotableSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.md),
            content = content,
        )
        NDivider()
    }
}
