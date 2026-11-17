package com.ethran.notable.ui.components

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.sync.automerge.LibrarySearch.SortKey
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.Paper
import com.ethran.notable.ui.viewmodels.LibraryBrowse
import com.ethran.notable.ui.viewmodels.QueryFilters
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Search

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

private val STATES = listOf("reading" to R.string.filter_reading, "unread" to R.string.filter_unread, "finished" to R.string.filter_finished)

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

private val LABEL = 16.sp
private val BAND = 34.dp // drawn height of pills and segments; their hit areas are 48 dp

/**
 * The controls above the notebooks (docs/plans/ebook-features.md, "Library bar design (round 4)"):
 * two lines. 1: an underline search field with ✕ and `?`, "Recent" (the notebooks opened last;
 * a tap opens one), and the sort (the key opens the menu, the arrow flips it). 2: All · Reading · Unread · Finished, the facet menus, Clear; it scrolls
 * sideways and never wraps. Black on white, 1 px rules, selected = inverted, no animation; every
 * hit area is at least 48 dp tall. Filters are query tokens ([QueryFilters]).
 */
@Composable
fun LibraryBar(
    query: String,
    onQueryChange: (String) -> Unit,
    browse: LibraryBrowse,
    onToggleFilter: (field: String, value: String) -> Unit,
    onSort: (SortKey?, Boolean?) -> Unit,
    onOpenNotebook: (Notebook) -> Unit = {},
) {
    Column {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, onQueryChange, Modifier.weight(1f))
            Spacer(Modifier.width(24.dp))
            if (browse.recent.isNotEmpty()) {
                RecentControl(browse.recent, onOpenNotebook)
                Spacer(Modifier.width(12.dp))
            }
            SortControl(browse, onSort)
        }
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            StateSegments(query) { onQueryChange(QueryFilters.setState(query, it)) }
            Spacer(Modifier.width(20.dp))
            val facets = browse.facets
            if (facets != null) for (m in MENUS) {
                val values = m.pick(facets)
                if (values.isEmpty()) continue // a menu without values isn't shown
                FacetButton(m, values, query, onToggleFilter)
            }
            if (QueryFilters.hasFilters(query)) TextTarget("✕", description = stringResource(R.string.bar_clear)) {
                onQueryChange(QueryFilters.clearFilters(query))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    val hint = stringResource(R.string.bar_search_hint)
    var syntax by remember { mutableStateOf(false) }
    Row(modifier.fillMaxHeight().drawBehind {
        drawLine(Ink, Offset(0f, size.height - 1.dp.toPx() / 2), Offset(size.width, size.height - 1.dp.toPx() / 2), 1.dp.toPx())
    }, verticalAlignment = Alignment.CenterVertically) {
        Icon(FeatherIcons.Search, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            textStyle = MaterialTheme.typography.body1.copy(color = Ink, fontSize = LABEL),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(hint, fontSize = LABEL, color = Ink.copy(alpha = 0.6f))
                inner()
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = hint },
        )
        if (query.isNotEmpty()) TextTarget("✕", description = stringResource(R.string.library_search_clear)) { onQueryChange("") }
        Box {
            TextTarget("?", description = stringResource(R.string.bar_syntax)) { syntax = !syntax }
            if (syntax) ScaledAnchoredPopup(onDismissRequest = { syntax = false }) {
                Column(Modifier.widthIn(max = 560.dp).background(Paper).border(1.dp, Ink).padding(16.dp)) {
                    Text(stringResource(R.string.bar_syntax), style = MaterialTheme.typography.subtitle2, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.bar_syntax_lines), fontSize = 15.sp, color = Ink, lineHeight = 24.sp)
                }
            }
        }
    }
}

/** Two hit zones: the key name opens the sort menu, the arrow (↓ descending, ↑ ascending) flips it. */
@Composable
private fun SortControl(browse: LibraryBrowse, onSort: (SortKey?, Boolean?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val key = browse.sort.key
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextTarget(stringResource(sortLabel(key)) + " ▾") { open = !open }
            if (open) MenuPanel(onClose = { open = false }) {
                items(listOf<SortKey?>(null) + SortKey.entries) { k ->
                    val name = if (k == null) stringResource(R.string.sort_usual) else stringResource(sortLabel(k))
                    // Each key names its default direction on the right; the current one is checked.
                    SortRow(name, k?.let { stringResource(directionLabel(it, it.defaultDescending)) }, current = k == key) {
                        onSort(k, null) // a new key starts in its own default direction
                        open = false
                    }
                }
            }
        }
        if (key != null && key != SortKey.RELEVANCE) {
            // The actual direction: ↓ descending, ↑ ascending.
            TextTarget(if (browse.sort.isDescending) "↓" else "↑",
                description = stringResource(R.string.bar_flip) + ": " + stringResource(directionLabel(key, browse.sort.isDescending))) {
                onSort(key, !browse.sort.isDescending)
            }
        }
    }
}

/** "Recent ▾": the notebooks opened last, newest first, each with when; a tap opens it where it was left. */
@Composable
private fun RecentControl(recent: List<Notebook>, onOpen: (Notebook) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextTarget(stringResource(R.string.bar_recent) + " ▾") { open = !open }
        if (open) MenuPanel(onClose = { open = false }) {
            items(recent, key = { it.id }) { nb ->
                RecentRow(nb.title, nb.readAt?.let { whenOpened(it.time) }) {
                    open = false
                    onOpen(nb)
                }
            }
        }
    }
}

private fun whenOpened(at: Long): String = DateUtils.getRelativeTimeSpanString(
    at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

@Composable
private fun RecentRow(title: String, opened: String?, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = LABEL, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (opened != null) Text(opened, fontSize = 14.sp, color = Ink, modifier = Modifier.padding(start = 16.dp))
        }
        Divider(color = Ink, thickness = 1.dp)
    }
}

/** All · Reading · Unread · Finished: one choice, the `is:` token; several typed values mark each. */
@Composable
private fun StateSegments(query: String, onState: (String?) -> Unit) {
    val chosen = QueryFilters.selected(query, "is")
    val segments = listOf<Pair<String?, Int>>(null to R.string.bar_all) + STATES
    Row(verticalAlignment = Alignment.CenterVertically) {
        segments.forEachIndexed { i, (value, label) ->
            val on = if (value == null) chosen.isEmpty() else value in chosen
            Box(Modifier.height(48.dp).clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, role = Role.Tab) { onState(value) }, contentAlignment = Alignment.Center) {
                Box(Modifier.height(BAND).background(if (on) Ink else Paper).drawBehind {
                    val w = 1.dp.toPx()
                    drawLine(Ink, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                    drawLine(Ink, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                    drawLine(Ink, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    if (i == 0) drawLine(Ink, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
                }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(label), fontSize = LABEL, color = if (on) Paper else Ink)
                }
            }
        }
    }
}

/** "Author ▾" as text; with selections an inverted pill with the count: "Tag 2 ▾". */
@Composable
private fun FacetButton(m: FacetMenu, values: List<LibrarySearch.Facet>, query: String, onToggle: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = QueryFilters.selected(query, m.field).size
    Box {
        Box(Modifier.height(48.dp).clickable(interactionSource = remember { MutableInteractionSource() },
            indication = null, role = Role.Button) { open = !open }
            .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            val label = stringResource(m.label) + (if (chosen > 0) " $chosen" else "") + " ▾"
            Box(Modifier.height(BAND).then(if (chosen > 0) Modifier.background(Ink, RoundedCornerShape(BAND / 2)) else Modifier)
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = LABEL, color = if (chosen > 0) Paper else Ink)
            }
        }
        // Anchored under its button; a tap toggles a value and leaves the menu open.
        if (open) MenuPanel(onClose = { open = false }) {
            items(values, key = { it.value }) { f ->
                ChoiceRow(stringResource(R.string.filter_value_count, m.name(f.value), f.count),
                    checked = QueryFilters.isSelected(query, m.field, f.value)) { onToggle(m.field, f.value) }
            }
        }
    }
}

/** Text with a hit area at least 48 dp tall. */
@Composable
private fun TextTarget(text: String, description: String? = null, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 40.dp)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = LABEL, color = Ink)
    }
}

/**
 * A menu under its button ([ScaledAnchoredPopup]): flat, bordered, rows of at least 56 dp, and a
 * scroll area capped to most of the screen. No animation.
 */
@Composable
private fun MenuPanel(onClose: () -> Unit, content: LazyListScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    ScaledAnchoredPopup(onDismissRequest = onClose) {
        Column(Modifier.widthIn(min = 280.dp, max = 480.dp).heightIn(max = maxHeight)
            .background(Paper).border(1.dp, Ink).padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.autoEInkAnimationOnScroll(), content = content)
        }
    }
}

/** A sort key: a check in the left gutter when current, the name, its default direction on the right. */
@Composable
private fun SortRow(name: String, direction: String?, current: Boolean, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(32.dp)) {
                if (current) Icon(FeatherIcons.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
            }
            Text(name, fontSize = LABEL, color = Ink, modifier = Modifier.weight(1f))
            if (direction != null) Text(direction, fontSize = 14.sp, color = Ink, modifier = Modifier.padding(start = 16.dp))
        }
        Divider(color = Ink, thickness = 1.dp)
    }
}

@Composable
private fun ChoiceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Checkbox, onClick = onClick)
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).border(1.dp, Ink).background(if (checked) Ink else Paper), contentAlignment = Alignment.Center) {
                if (checked) Icon(FeatherIcons.Check, contentDescription = null, tint = Paper, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(label, fontSize = LABEL, color = Ink)
        }
        Divider(color = Ink, thickness = 1.dp)
    }
}

/** The results line: "3 matches in Uni · Search everywhere" (the scope switch is a text link). */
@Composable
fun ResultsLine(count: Int, folderTitle: String?, everywhere: Boolean, onEverywhere: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        val inFolder = folderTitle != null && !everywhere
        Text(if (inFolder) pluralStringResource(R.plurals.bar_matches_in, count, count, folderTitle!!)
            else pluralStringResource(R.plurals.bar_matches, count, count),
            fontSize = LABEL, color = Ink)
        if (folderTitle != null) {
            Text("  ·", fontSize = LABEL, color = Ink)
            LinkTarget(if (everywhere) stringResource(R.string.bar_search_only, folderTitle) else stringResource(R.string.bar_search_everywhere)) {
                onEverywhere(!everywhere)
            }
        }
    }
}

@Composable
private fun RowScope.LinkTarget(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(interactionSource = remember { MutableInteractionSource() },
        indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = LABEL, color = Ink, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
    }
}
