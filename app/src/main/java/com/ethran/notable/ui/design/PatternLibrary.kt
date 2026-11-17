package com.ethran.notable.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.ui.components.ScaledDialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Share
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload

/**
 * Every component of the design system in each of its states, on the device (Settings → Debug →
 * Pattern library). Use it to judge changes on the real panel before touching screens.
 */
@Composable
fun PatternLibraryDialog(onDismiss: () -> Unit) {
    ScaledDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(NotableTheme.colors.paper)) {
            NTopBar("Pattern library", onBack = onDismiss, meta = "Tokens · atoms · molecules · organisms")
            PatternLibrary(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = NotableSpace.gutter))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternLibrary(modifier: Modifier = Modifier) {
    val c = NotableTheme.colors
    val t = NotableTheme.type
    var text by remember { mutableStateOf("ais-slides") }
    var slider by remember { mutableStateOf(1f) }
    var toggle by remember { mutableStateOf(true) }
    var tpl by remember { mutableStateOf(0) }
    Column(modifier.fillMaxWidth().padding(bottom = NotableSpace.xxxl)) {

        // ── Tokens
        Group("Tokens") {
            Sub("Color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg), verticalArrangement = Arrangement.spacedBy(NotableSpace.lg)) {
                Swatch("ink", c.ink); Swatch("paper", c.paper); Swatch("inset", c.inset)
                Swatch("secondary", c.secondary); Swatch("rule", c.rule); Swatch("disabled", c.disabled)
            }
            Sub("Type")
            TypeSample("Display · serif 30", t.display)
            TypeSample("Title · serif 24", t.title)
            TypeSample("Heading · serif 19", t.heading)
            TypeSample("Body · sans 16. The quick brown fox jumps over the lazy dog.", t.body)
            TypeSample("Label · sans 15 medium", t.label)
            TypeSample("Meta · sans 13 — 4 pages · edited yesterday", t.meta, c.secondary)
            TypeSample("mono 12 · adb forward tcp:7357 tcp:7357", t.mono)
            Sub("Space and shape")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
                listOf(4, 8, 12, 16, 24, 32, 48).forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(s.dp).background(c.ink))
                        Text("$s", style = t.meta, color = c.secondary)
                    }
                }
                Spacer(Modifier.width(NotableSpace.xxl))
                Box(Modifier.size(64.dp, 44.dp).border(1.dp, c.ink, NotableShape.control))
                Box(Modifier.size(88.dp, 64.dp).border(1.dp, c.ink, NotableShape.surface))
                Box(Modifier.size(48.dp, 64.dp).border(1.dp, c.rule, NotableShape.paper))
            }
        }

        // ── Atoms
        Group("Atoms") {
            Sub("Buttons")
            Line {
                NButton("New notebook", {}, style = NButtonStyle.Primary, icon = FeatherIcons.FilePlus)
                NButton("Import", {}, icon = FeatherIcons.Upload)
                NButton("Quiet", {}, style = NButtonStyle.Quiet)
                NButton("Delete notebook", {}, style = NButtonStyle.Danger, icon = FeatherIcons.Trash2)
            }
            Line {
                NButton("Primary", {}, style = NButtonStyle.Primary, enabled = false)
                NButton("Secondary", {}, enabled = false)
                NButton("Selected filter", {}, selected = true)
            }
            Line {
                NButton("Compact", {}, size = NControlSize.Compact, style = NButtonStyle.Primary)
                NButton("Compact", {}, size = NControlSize.Compact, icon = FeatherIcons.Plus)
                NButton("Compact", {}, size = NControlSize.Compact, style = NButtonStyle.Quiet)
                NLink("Search everywhere", {})
            }
            Sub("Icon buttons")
            Line {
                NIconButton(FeatherIcons.Settings, "Settings", {})
                NIconButton(FeatherIcons.MoreHorizontal, "More", {})
                NIconButton(FeatherIcons.Edit3, "Selected", {}, selected = true)
                NIconButton(FeatherIcons.Share, "Outlined", {}, outlined = true)
                NIconButton(FeatherIcons.Download, "Disabled", {}, enabled = false)
                NIconButton(FeatherIcons.HelpCircle, "Compact", {}, size = NControlSize.Compact)
            }
            Sub("Selection")
            var sw by remember { mutableStateOf(true) }
            var cb by remember { mutableStateOf(true) }
            var radio by remember { mutableIntStateOf(0) }
            Line {
                NSwitch(sw, { sw = it }); NSwitch(!sw, { sw = !it }); NSwitch(true, null, enabled = false)
                Spacer(Modifier.width(NotableSpace.xl))
                NCheckbox(cb, { cb = it }); NCheckbox(!cb, { cb = !it }); NCheckbox(true, null, enabled = false)
                Spacer(Modifier.width(NotableSpace.xl))
                NRadio(radio == 0, { radio = 0 }); NRadio(radio == 1, { radio = 1 })
            }
            Sub("Text field")
            var empty by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg)) {
                NTextField(text, { text = it }, Modifier.weight(1f))
                NTextField(empty, { empty = it }, Modifier.weight(1f), placeholder = "Placeholder")
                NTextField("Disabled", {}, Modifier.weight(1f), enabled = false)
            }
            Sub("Slider, divider, badges")
            NSlider(slider, 0.5f..1.5f, 9, { slider = it }, {})
            Spacer(Modifier.height(NotableSpace.md))
            NDivider()
            Spacer(Modifier.height(NotableSpace.md))
            Line { NBadge("PDF"); NBadge("3", filled = true); NBadge("Finished") }
        }

        // ── Molecules
        Group("Molecules") {
            Sub("Search field")
            var q by remember { mutableStateOf("") }
            NSearchField(q, { q = it }, "Search titles, authors, tags…", "Clear search", trailing = {
                NIconButton(FeatherIcons.HelpCircle, "Query syntax", {}, size = NControlSize.Compact)
            })
            Sub("Segmented and select")
            var seg by remember { mutableStateOf("all") }
            var sort by remember { mutableStateOf("progress") }
            Line {
                NSegmented(listOf("all" to "All", "reading" to "Reading", "unread" to "Unread", "finished" to "Finished"), seg, { seg = it })
                NSelect(listOf("title" to "Title", "progress" to "Progress", "added" to "Added"), sort, { sort = it })
                NSelectTrigger("Type", {})
                NSelectTrigger("Tag 2", {}, selected = true)
            }
            Line {
                NSegmented(listOf("all" to "All", "reading" to "Reading"), seg, { seg = it }, size = NControlSize.Compact)
                NSelect(listOf("title" to "Title", "progress" to "Progress"), sort, { sort = it }, size = NControlSize.Compact)
            }
            Sub("Settings rows")
            Column(Modifier.widthIn(max = 720.dp)) {
                NToggleRow("Smooth scrolling", toggle, { toggle = it })
                NToggleRow("Steadier auto-rotate", !toggle, { toggle = !it },
                    description = "Turn only on a deliberate tilt, held briefly, and not while writing.")
                NSelectRow("Default page template", listOf(0 to "Blank page", 1 to "Lined", 2 to "Dotted"), tpl, { tpl = it })
                NSliderRow("UI scale", slider, 0.5f..1.5f, 9, { slider = it })
                NSettingRow("Copy notebooks into the sync library", description = "Safe to run again: anything already copied is skipped.") {
                    NButton("Copy", {}, size = NControlSize.Compact)
                }
            }
            Sub("Field and notice")
            Row(horizontalArrangement = Arrangement.spacedBy(NotableSpace.xl)) {
                NField("Name", Modifier.weight(1f), help = "Shown under the cover.") { NTextField(text, { text = it }, Modifier.fillMaxWidth()) }
                NField("Server", Modifier.weight(1f), error = "Can't reach the server. Check the address.") { NTextField("notable.example", {}, Modifier.fillMaxWidth()) }
            }
            Spacer(Modifier.height(NotableSpace.lg))
            NNotice("Battery optimisation may pause sync in the background. Exempt Notable in the battery settings.",
                title = "Sync can pause", actions = { NButton("Open battery settings", {}, size = NControlSize.Compact) })
            Sub("Tabs")
            var tab by remember { mutableIntStateOf(0) }
            NTabs(listOf("General", "Gestures", "Toolbar", "Sync", "Debug"), tab, { tab = it })
            Sub("Menu rows (as they appear inside a menu)")
            Column(Modifier.width(360.dp).nSurface(c).padding(vertical = 6.dp)) {
                NMenuHeader("Page")
                NMenuItem("Share page as PDF", {}, icon = FeatherIcons.Share)
                NMenuItem("Export page", {}, icon = FeatherIcons.Download, meta = "PDF, PNG, xopp")
                NMenuDivider()
                NMenuItem("Title", {}, checked = false, meta = "A to Z")
                NMenuItem("Progress", {}, checked = true, meta = "Furthest first")
                NMenuDivider()
                NMenuCheckItem("Philosophy", checked = true, onToggle = {}, meta = "12")
                NMenuCheckItem("Design", checked = false, onToggle = {}, meta = "4")
            }
        }

        // ── Organisms
        Group("Organisms") {
            Sub("Top bar")
            Box(Modifier.border(1.dp, c.rule)) {
                NTopBar("Settings", onBack = {}, meta = "v0.2.6") { NIconButton(FeatherIcons.HelpCircle, "Help", {}) }
            }
            Sub("Dialog (inline frame)")
            var dialog by remember { mutableStateOf(false) }
            var confirm by remember { mutableStateOf(false) }
            Line {
                NButton("Open dialog", { dialog = true })
                NButton("Open confirmation", { confirm = true })
                Box {
                    var menu by remember { mutableStateOf(false) }
                    NSelectTrigger("Open menu", { menu = true })
                    if (menu) NMenu({ menu = false }) {
                        NMenuItem("Rename", { menu = false }, icon = FeatherIcons.Edit3)
                        NMenuItem("Move to folder", { menu = false }, icon = FeatherIcons.Folder)
                        NMenuDivider()
                        NMenuItem("Delete", { menu = false }, icon = FeatherIcons.Trash2)
                    }
                }
            }
            if (dialog) NDialog("Notebook details", { dialog = false },
                titleAction = { NButton("Done", { dialog = false }, style = NButtonStyle.Primary) }) {
                NField("Name") { NTextField(text, { text = it }, Modifier.fillMaxWidth()) }
                Spacer(Modifier.height(NotableSpace.xl))
                NSelectRow("Page template", listOf(0 to "Blank page", 1 to "Lined"), tpl, { tpl = it })
                NToggleRow("Open on the last page", toggle, { toggle = it })
                Spacer(Modifier.height(NotableSpace.xl))
                Line {
                    NButton("Move", {}, icon = FeatherIcons.FolderPlus)
                    NButton("Export", {}, icon = FeatherIcons.Download)
                }
                Spacer(Modifier.height(NotableSpace.xl))
                NDivider()
                Spacer(Modifier.height(NotableSpace.xl))
                NButton("Delete notebook", {}, style = NButtonStyle.Danger, icon = FeatherIcons.Trash2)
            }
            if (confirm) NConfirmDialog("Delete notebook?", "“ais-slides” and its 4 pages will be removed from every device.",
                "Delete", { confirm = false }, { confirm = false }, destructive = true)
            Sub("Section and empty state")
            NSection("Live code", description = "Commands written in JavaScript, synced through your library.") {
                NToggleRow("Safe mode", false, {}, description = "Built-in commands only, from the next start.")
            }
            NEmptyState("No notebooks here yet", message = "Create a notebook or import a PDF to fill this folder.",
                action = { NButton("New notebook", {}, style = NButtonStyle.Primary, icon = FeatherIcons.FilePlus) })
            Sub("Code block")
            NCodeBlock("java.lang.NullPointerException: Attempt to invoke interface method\n  at com.ethran.notable.ui.viewmodels.LibraryViewModel.refresh(LibraryViewModel.kt:276)")
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(NotableSpace.xxl))
    Text(title, style = NotableTheme.type.display, color = NotableTheme.colors.ink)
    Spacer(Modifier.height(NotableSpace.sm))
    NDivider(strong = true)
    content()
}

@Composable
private fun Sub(title: String) {
    Spacer(Modifier.height(NotableSpace.xl))
    Text(title, style = NotableTheme.type.heading, color = NotableTheme.colors.ink)
    Spacer(Modifier.height(NotableSpace.md))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Line(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.md),
        verticalArrangement = Arrangement.spacedBy(NotableSpace.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
    Spacer(Modifier.height(NotableSpace.sm))
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column {
        Box(Modifier.size(96.dp, 56.dp).background(color, NotableShape.control).border(1.dp, NotableTheme.colors.rule, NotableShape.control))
        Text(name, style = NotableTheme.type.meta, color = NotableTheme.colors.secondary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TypeSample(text: String, style: TextStyle, color: Color = NotableTheme.colors.ink) {
    Text(text, style = style, color = color, modifier = Modifier.padding(vertical = 4.dp))
}
