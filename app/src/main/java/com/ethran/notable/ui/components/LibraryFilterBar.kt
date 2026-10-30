package com.ethran.notable.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.sync.automerge.LibrarySearch.SortKey
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.Paper
import com.ethran.notable.ui.theme.PaperLine
import com.ethran.notable.ui.viewmodels.LibraryBrowse
import com.ethran.notable.ui.viewmodels.QueryFilters
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown

/** A facet menu: its query field, its label, and which facet list fills it. */
private data class FacetMenu(val field: String, @StringRes val label: Int, val pick: (LibrarySearch.Facets) -> List<LibrarySearch.Facet>)

private val MENUS = listOf(
    FacetMenu("author", R.string.filter_author) { it.authors },
    FacetMenu("series", R.string.filter_series) { it.series },
    FacetMenu("tag", R.string.filter_tag) { it.tags },
    FacetMenu("lang", R.string.filter_language) { it.languages },
)

private val STATES = listOf("unread" to R.string.filter_unread, "reading" to R.string.filter_reading, "finished" to R.string.filter_finished)
private val KINDS = listOf("pdf" to R.string.filter_pdf, "epub" to R.string.filter_epub,
    "notebook" to R.string.filter_notebook, "quickpage" to R.string.filter_quick_page)

@StringRes
private fun sortLabel(key: SortKey?) = when (key) {
    null -> R.string.sort_usual
    SortKey.RELEVANCE -> R.string.sort_relevance
    SortKey.TITLE -> R.string.sort_title
    SortKey.AUTHOR -> R.string.sort_author
    SortKey.SERIES -> R.string.sort_series
    SortKey.PUBLISHED -> R.string.sort_published
    SortKey.ADDED -> R.string.sort_added
    SortKey.EDITED -> R.string.sort_edited
    SortKey.READ -> R.string.sort_read
    SortKey.FINISHED -> R.string.sort_finished
    SortKey.LENGTH -> R.string.sort_length
    SortKey.PROGRESS -> R.string.sort_progress
    SortKey.RATING -> R.string.sort_rating
}

/**
 * Filters and sort (docs/plans/ebook-features.md, "Filters and sort"). Every filter is a token in
 * the query text ([QueryFilters]), so the search field always shows what is filtered. Flat chips,
 * menus as dialogs, no animation: e-ink.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterBar(
    query: String,
    browse: LibraryBrowse,
    onToggleFilter: (field: String, value: String) -> Unit,
    onSort: (SortKey?, Boolean?) -> Unit,
    onEverywhere: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf<FacetMenu?>(null) }
    var sorting by remember { mutableStateOf(false) }
    val facets = browse.facets
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in STATES) PaperAction(stringResource(label), onClick = { onToggleFilter("is", value) },
                primary = QueryFilters.isSelected(query, "is", value))
            Spacer(Modifier.width(8.dp))
            for ((value, label) in KINDS) PaperAction(stringResource(label), onClick = { onToggleFilter("kind", value) },
                primary = QueryFilters.isSelected(query, "kind", value))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = if (facets == null) emptyList() else MENUS.filter { m -> m.pick(facets).isNotEmpty() }
            for (m in shown) Box {
                val chosen = QueryFilters.selected(query, m.field).size
                val label = stringResource(m.label) + if (chosen > 0) " ($chosen)" else ""
                PaperAction(label, onClick = { menu = if (menu == m) null else m }, icon = FeatherIcons.ChevronDown, primary = chosen > 0)
                // Anchored under its button; a tap toggles a value and leaves the menu open.
                if (menu == m) MenuPanel(onClose = { menu = null }) {
                    items(m.pick(facets!!), key = { it.value }) { f ->
                        ChoiceRow(stringResource(R.string.filter_value_count, f.value, f.count),
                            checked = QueryFilters.isSelected(query, m.field, f.value)) { onToggleFilter(m.field, f.value) }
                    }
                }
            }
            if (shown.isNotEmpty()) Spacer(Modifier.width(8.dp))
            Box {
                PaperAction(stringResource(R.string.sort_label, stringResource(sortLabel(browse.sort.key))),
                    onClick = { sorting = !sorting }, icon = FeatherIcons.ChevronDown)
                if (sorting) MenuPanel(onClose = { sorting = false }) {
                    items(listOf<SortKey?>(null) + SortKey.entries) { key ->
                        ChoiceRow(stringResource(sortLabel(key)), checked = key == browse.sort.key) {
                            // A new key starts in its own default direction.
                            onSort(key, null)
                            sorting = false
                        }
                    }
                }
            }
            if (browse.sort.key != null && browse.sort.key != SortKey.RELEVANCE) {
                val down = browse.sort.isDescending
                PaperAction(stringResource(if (down) R.string.sort_descending else R.string.sort_ascending),
                    icon = if (down) FeatherIcons.ArrowDown else FeatherIcons.ArrowUp,
                    onClick = { onSort(browse.sort.key, !down) })
            }
            // In a folder a query covers the folder and its subfolders; this widens it.
            if (browse.inFolder && query.isNotBlank()) PaperAction(stringResource(R.string.filter_everywhere),
                onClick = { onEverywhere(!browse.everywhere) }, primary = browse.everywhere)
        }
    }

}

/**
 * A menu under its button ([ScaledAnchoredPopup]): flat, bordered, rows of at least 56 dp, and a
 * scroll area capped to most of the screen. No animation.
 */
@Composable
private fun MenuPanel(onClose: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    ScaledAnchoredPopup(onDismissRequest = onClose) {
        Column(Modifier.widthIn(min = 280.dp, max = 480.dp).heightIn(max = maxHeight)
            .background(Paper).border(1.dp, Ink).padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.autoEInkAnimationOnScroll(), content = content)
        }
    }
}

@Composable
private fun ChoiceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Checkbox, onClick = onClick)
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.size(28.dp).border(1.dp, if (checked) Ink else PaperLine), verticalAlignment = Alignment.CenterVertically) {
                if (checked) Icon(FeatherIcons.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.body1, color = Ink)
        }
        Divider(color = PaperLine)
    }
}
