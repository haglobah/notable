package com.ethran.notable.ui.components

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.sync.automerge.LibrarySearch.SortKey
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NLazyMenu
import com.ethran.notable.ui.design.NLink
import com.ethran.notable.ui.design.NMenuCheckItem
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NSearchField
import com.ethran.notable.ui.design.NSegmented
import com.ethran.notable.ui.design.NSelectTrigger
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.NPopover
import com.ethran.notable.ui.design.nSurface
import com.ethran.notable.ui.viewmodels.LibraryBrowse
import com.ethran.notable.ui.viewmodels.QueryFilters
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.X

/** A facet menu: its query field, its label, the facet list that fills it, and how a value reads. */
private data class FacetMenu(
    val field: String, @StringRes val label: Int,
    val pick: (LibrarySearch.Facets) -> List<LibrarySearch.Facet>,
    val name: @Composable (String) -> String = { it },
)

private val KIND_NAMES = mapOf("pdf" to R.string.filter_pdf, "epub" to R.string.filter_epub,
    "notebook" to R.string.filter_notebook, "quickpage" to R.string.filter_quick_page)

private val MENUS = listOf(
    FacetMenu("author", R.string.filter_author, { it.authors }),
    FacetMenu("series", R.string.filter_series, { it.series }),
    FacetMenu("tag", R.string.filter_tag, { it.tags }),
    FacetMenu("lang", R.string.filter_language, { it.languages }),
    // Only kinds the library has (facets are counted from it).
    FacetMenu("kind", R.string.bar_type, { it.kinds }, { v -> KIND_NAMES[v]?.let { stringResource(it) } ?: v }),
)

private val STATES = listOf("reading" to R.string.filter_reading, "unread" to R.string.filter_unread,
    "finished" to R.string.filter_finished, "next" to R.string.filter_next)

@StringRes
private fun sortLabel(key: SortKey?) = when (key) {
    null -> R.string.bar_sort
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

/** What a direction means for [key]: "A to Z", "Newest first", "Furthest first", … */
@StringRes
private fun directionLabel(key: SortKey, descending: Boolean) = when (key) {
    SortKey.RELEVANCE -> R.string.dir_best
    SortKey.TITLE, SortKey.AUTHOR, SortKey.SERIES -> if (descending) R.string.dir_za else R.string.dir_az
    SortKey.PUBLISHED, SortKey.ADDED, SortKey.EDITED, SortKey.READ, SortKey.FINISHED ->
        if (descending) R.string.dir_newest else R.string.dir_oldest
    SortKey.LENGTH -> if (descending) R.string.dir_longest else R.string.dir_shortest
    SortKey.PROGRESS -> if (descending) R.string.dir_furthest else R.string.dir_least
    SortKey.RATING -> if (descending) R.string.dir_highest else R.string.dir_lowest
}

/**
 * The controls above the notebooks (docs/plans/ebook-features.md, "Library bar design (round 4)";
 * docs/plans/design-system.md): two rows of 44 dp controls from the design system.
 * 1: the search field (clear, query syntax), "Recent" (the notebooks opened last; a tap opens
 * one), the sort (the key opens the menu, the arrow flips it), then [trailing] (the settings gear).
 * "Read next" sits after "Recent" when the list has books (docs/plans/library-features.md).
 * 2: All · Reading · Unread · Finished · Read next, the facet menus, Hide finished, Clear; it scrolls sideways and never
 * wraps. Filters are query tokens ([QueryFilters]).
 */
@Composable
fun LibraryBar(
    query: String,
    onQueryChange: (String) -> Unit,
    browse: LibraryBrowse,
    onToggleFilter: (field: String, value: String) -> Unit,
    onSort: (SortKey?, Boolean?) -> Unit,
    onOpenNotebook: (Notebook) -> Unit = {},
    /** Hide finished books (docs/plans/library-features.md); remembered per device. */
    onHideFinished: (Boolean) -> Unit = {},
    /** At the far right of the search row: navigation such as the settings gear. */
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
            SearchField(query, onQueryChange, Modifier.weight(1f))
            if (browse.recent.isNotEmpty()) RecentControl(browse.recent, onOpenNotebook)
            if (browse.readNext.isNotEmpty()) ReadNextControl(browse.readNext, onOpenNotebook)
            SortControl(browse, onSort)
            trailing()
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
            StateSegments(query) { onQueryChange(QueryFilters.setState(query, it)) }
            Spacer(Modifier.width(NotableSpace.sm))
            val facets = browse.facets
            if (facets != null) for (m in MENUS) {
                val values = m.pick(facets)
                if (values.isEmpty()) continue // a menu without values isn't shown
                FacetButton(m, values, query, onToggleFilter)
            }
            NButton(stringResource(R.string.bar_hide_finished), onClick = { onHideFinished(!browse.hideFinished) },
                selected = browse.hideFinished, icon = FeatherIcons.EyeOff)
            if (QueryFilters.hasFilters(query)) NButton(stringResource(R.string.bar_clear),
                onClick = { onQueryChange(QueryFilters.clearFilters(query)) },
                style = NButtonStyle.Quiet, icon = FeatherIcons.X)
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    val hint = stringResource(R.string.bar_search_hint)
    var syntax by remember { mutableStateOf(false) }
    NSearchField(
        query = query, onQueryChange = onQueryChange, placeholder = hint,
        clearDescription = stringResource(R.string.library_search_clear),
        modifier = modifier.semantics { contentDescription = hint },
        trailing = {
            Box {
                NIconButton(FeatherIcons.HelpCircle, stringResource(R.string.bar_syntax), { syntax = !syntax },
                    size = NControlSize.Compact, selected = syntax)
                if (syntax) SyntaxHelp(onDismiss = { syntax = false })
            }
        },
    )
}

/** The query syntax, under the `?`: a menu surface with a heading and the tokens in mono. */
@Composable
private fun SyntaxHelp(onDismiss: () -> Unit) =
    NPopover(stringResource(R.string.bar_syntax), onDismiss) {
        Text(stringResource(R.string.bar_syntax_lines), style = NotableTheme.type.mono, color = NotableTheme.colors.ink)
    }

/** Two hit zones: the key name opens the sort menu, the arrow (down descending, up ascending) flips it. */
@Composable
private fun SortControl(browse: LibraryBrowse, onSort: (SortKey?, Boolean?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val key = browse.sort.key
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotableSpace.xs)) {
        Box {
            NSelectTrigger(stringResource(sortLabel(key)), { open = !open })
            if (open) NLazyMenu(onDismiss = { open = false }) {
                items(listOf<SortKey?>(null) + SortKey.entries) { k ->
                    val name = if (k == null) stringResource(R.string.sort_usual) else stringResource(sortLabel(k))
                    // Each key names its default direction on the right; the current one is checked.
                    NMenuItem(name, checked = k == key,
                        meta = k?.let { stringResource(directionLabel(it, it.defaultDescending)) },
                        onClick = {
                            onSort(k, null) // a new key starts in its own default direction
                            open = false
                        })
                }
            }
        }
        if (key != null && key != SortKey.RELEVANCE) {
            val descending = browse.sort.isDescending
            NIconButton(
                icon = if (descending) FeatherIcons.ArrowDown else FeatherIcons.ArrowUp,
                contentDescription = stringResource(R.string.bar_flip) + ": " + stringResource(directionLabel(key, descending)),
                onClick = { onSort(key, !descending) },
                outlined = true,
            )
        }
    }
}

/** "Recent": the notebooks opened last, newest first, each with when; a tap opens it where it was left. */
@Composable
private fun RecentControl(recent: List<Notebook>, onOpen: (Notebook) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NSelectTrigger(stringResource(R.string.bar_recent), { open = !open })
        if (open) NLazyMenu(onDismiss = { open = false }) {
            items(recent, key = { it.id }) { nb ->
                NMenuItem(nb.title, meta = nb.readAt?.let { whenOpened(it.time) }, onClick = {
                    open = false
                    onOpen(nb)
                })
            }
        }
    }
}

/** "Read next": the books put on the list, oldest first; a tap opens one. */
@Composable
private fun ReadNextControl(books: List<Notebook>, onOpen: (Notebook) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NSelectTrigger(stringResource(R.string.bar_read_next) + " ${books.size}", { open = !open })
        if (open) NLazyMenu(onDismiss = { open = false }) {
            items(books, key = { it.id }) { nb ->
                NMenuItem(nb.title, meta = nb.bookMeta?.authors?.firstOrNull(), onClick = {
                    open = false
                    onOpen(nb)
                })
            }
        }
    }
}

private fun whenOpened(at: Long): String = DateUtils.getRelativeTimeSpanString(
    at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

/** All · Reading · Unread · Finished: one choice, the `is:` token; several typed values mark each. */
@Composable
private fun StateSegments(query: String, onState: (String?) -> Unit) {
    val chosen = QueryFilters.selected(query, "is")
    val segments = listOf<Pair<String?, String>>(null to stringResource(R.string.bar_all)) +
        STATES.map { (value, label) -> value to stringResource(label) }
    NSegmented(
        options = segments, selected = null, onSelect = onState,
        isSelected = { value -> if (value == null) chosen.isEmpty() else value in chosen },
    )
}

/** "Author" as a select; with selections it is inverted and carries the count: "Tag 2". */
@Composable
private fun FacetButton(m: FacetMenu, values: List<LibrarySearch.Facet>, query: String, onToggle: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = QueryFilters.selected(query, m.field).size
    Box {
        NSelectTrigger(stringResource(m.label) + (if (chosen > 0) " $chosen" else ""), { open = !open },
            selected = chosen > 0)
        // Anchored under its button; a tap toggles a value and leaves the menu open.
        if (open) NLazyMenu(onDismiss = { open = false }) {
            items(values, key = { it.value }) { f ->
                NMenuCheckItem(m.name(f.value), checked = QueryFilters.isSelected(query, m.field, f.value),
                    onToggle = { onToggle(m.field, f.value) }, meta = f.count.toString())
            }
        }
    }
}

/** The results line: "3 matches in Uni · Search everywhere" (the scope switch is a text link). */
@Composable
fun ResultsLine(count: Int, folderTitle: String?, everywhere: Boolean, onEverywhere: (Boolean) -> Unit) {
    val c = NotableTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        val inFolder = folderTitle != null && !everywhere
        Text(if (inFolder) pluralStringResource(R.plurals.bar_matches_in, count, count, folderTitle!!)
            else pluralStringResource(R.plurals.bar_matches, count, count),
            style = NotableTheme.type.body, color = c.ink)
        if (folderTitle != null) {
            Text("·", style = NotableTheme.type.body, color = c.secondary)
            NLink(if (everywhere) stringResource(R.string.bar_search_only, folderTitle) else stringResource(R.string.bar_search_everywhere),
                onClick = { onEverywhere(!everywhere) })
        }
    }
}
