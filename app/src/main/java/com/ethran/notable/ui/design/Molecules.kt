package com.ethran.notable.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Info
import compose.icons.feathericons.Search
import compose.icons.feathericons.X

// ───────────────────────────── Segmented control ─────────────────────────────

/** One choice out of a few: a bordered strip, the chosen segment filled with ink. */
@Composable
fun <T> NSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    size: NControlSize = NControlSize.Regular,
    /** Several segments may be chosen (e.g. several typed `is:` tokens); overrides [selected]. */
    isSelected: (T) -> Boolean = { it == selected },
) {
    val c = NotableTheme.colors
    Box(modifier.heightIn(min = NotableSize.control), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(if (size == NControlSize.Regular) NotableSize.control else NotableSize.compact)
                .width(IntrinsicSize.Max)
                .clip(NotableShape.control)
                .border(NotableSize.hairline, c.ink, NotableShape.control),
        ) {
            options.forEachIndexed { i, (value, label) ->
                val on = isSelected(value)
                if (i > 0) Box(Modifier.width(NotableSize.hairline).fillMaxHeight().background(c.ink))
                Box(
                    Modifier.fillMaxHeight().background(if (on) c.ink else c.paper)
                        .selectable(on, interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.Tab) { onSelect(value) }
                        .padding(horizontal = NotableSpace.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = NotableTheme.type.label, color = if (on) c.paper else c.ink, maxLines = 1)
                }
            }
        }
    }
}

// ───────────────────────────── Select ─────────────────────────────

enum class NSelectStyle {
    /** An outlined control with the value and a chevron, for forms and bars. */
    Field,
    /** The value and a chevron as quiet text, for the trailing side of a settings row. */
    Inline,
}

/**
 * A dropdown: shows the current value; a tap opens an [NMenu] under it with every option, the
 * current one checked.
 */
@Composable
fun <T> NSelect(
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: NSelectStyle = NSelectStyle.Field,
    size: NControlSize = NControlSize.Regular,
    /** Shown before the value: "Sort: Title". */
    prefix: String? = null,
    placeholder: String = "—",
) {
    var open by remember { mutableStateOf(false) }
    val current = options.find { it.first == value }?.second ?: placeholder
    Box(modifier) {
        NSelectTrigger(
            text = if (prefix != null) "$prefix: $current" else current,
            onClick = { open = true }, style = style, size = size,
        )
        if (open) NMenu(onDismiss = { open = false }) {
            options.forEach { (v, label) ->
                NMenuItem(label, checked = v == value, onClick = { onChange(v); open = false })
            }
        }
    }
}

/** The face of a select or of any button that opens a menu: text + chevron. */
@Composable
fun NSelectTrigger(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NSelectStyle = NSelectStyle.Field,
    size: NControlSize = NControlSize.Regular,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    if (style == NSelectStyle.Field) {
        NButton(text, onClick, modifier, style = NButtonStyle.Secondary, size = size,
            trailingIcon = FeatherIcons.ChevronDown, selected = selected, enabled = enabled)
    } else {
        val c = NotableTheme.colors
        Row(
            modifier.heightIn(min = NotableSize.control).inkClickable(enabled = enabled, onClick = onClick)
                .padding(start = NotableSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = NotableTheme.type.body, color = if (enabled) c.secondary else c.disabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(NotableSpace.xs + NotableSpace.xxs))
            NIcon(FeatherIcons.ChevronDown, tint = if (enabled) c.ink else c.disabled, size = 18.dp)
        }
    }
}

// ───────────────────────────── Search field ─────────────────────────────

/** A text field with a search icon and a clear button; [trailing] adds more icon buttons. */
@Composable
fun NSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearDescription: String,
    modifier: Modifier = Modifier,
    /** Runs on the keyboard's search key, for searches that aren't live. */
    onSearch: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    NTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = FeatherIcons.Search,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = if (onSearch != null) KeyboardActions(onSearch = { onSearch() }) else KeyboardActions.Default,
        trailing = {
            if (query.isNotEmpty()) NIconButton(FeatherIcons.X, clearDescription, { onQueryChange("") }, size = NControlSize.Compact)
            trailing()
        },
    )
}

// ───────────────────────────── Menu rows ─────────────────────────────

/**
 * A row in an [NMenu]. [checked] = null: no check column. true/false: a check column on the
 * left that marks the current choice (single choice). [meta] goes on the right.
 */
@Composable
fun NMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    meta: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
) {
    val c = NotableTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = NotableSize.menuRow)
            .inkClickable(enabled = enabled, role = if (checked != null) Role.RadioButton else Role.Button, onClick = onClick)
            .padding(horizontal = NotableSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            Box(Modifier.width(28.dp)) { if (checked) NIcon(FeatherIcons.Check, size = 18.dp) }
        }
        if (icon != null) {
            NIcon(icon, tint = if (enabled) c.ink else c.disabled)
            Spacer(Modifier.width(NotableSpace.md))
        }
        Text(text, style = NotableTheme.type.body, color = if (enabled) c.ink else c.disabled,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (meta != null) Text(meta, style = NotableTheme.type.meta, color = c.secondary, maxLines = 1,
            modifier = Modifier.padding(start = NotableSpace.lg))
    }
}

/** A row in an [NMenu] that toggles a value and leaves the menu open (multi-select). */
@Composable
fun NMenuCheckItem(text: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, meta: String? = null) {
    val c = NotableTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = NotableSize.menuRow)
            .toggleable(checked, interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Checkbox) { onToggle() }
            .padding(horizontal = NotableSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NCheckbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(NotableSpace.md))
        Text(text, style = NotableTheme.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (meta != null) Text(meta, style = NotableTheme.type.meta, color = c.secondary, modifier = Modifier.padding(start = NotableSpace.lg))
    }
}

/** Separates groups in a menu. */
@Composable
fun NMenuDivider() {
    NDivider(Modifier.padding(vertical = NotableSpace.xs + NotableSpace.xxs))
}

/** A small heading over a group in a menu. */
@Composable
fun NMenuHeader(text: String) {
    Text(text, style = NotableTheme.type.meta, color = NotableTheme.colors.secondary,
        modifier = Modifier.padding(start = NotableSpace.lg, end = NotableSpace.lg, top = NotableSpace.md, bottom = NotableSpace.xs))
}

// ───────────────────────────── Settings rows ─────────────────────────────

/**
 * A settings row: title and optional description on the left, a control on the right, a
 * hairline under it. With [onClick] the whole row is the hit area.
 */
@Composable
fun NSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = NotableTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NotableSize.row)
                .then(if (onClick != null) Modifier.inkClickable(enabled = enabled, onClick = onClick) else Modifier)
                .padding(vertical = NotableSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = NotableSpace.lg)) {
                Text(title, style = NotableTheme.type.body, color = if (enabled) c.ink else c.disabled)
                if (description != null) Text(description, style = NotableTheme.type.meta, color = c.secondary,
                    modifier = Modifier.padding(top = NotableSpace.xxs))
            }
            if (trailing != null) trailing()
        }
        if (divider) NDivider()
    }
}

/** A setting that is on or off; the whole row toggles it. */
@Composable
fun NToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    NSettingRow(
        title = title, description = description, enabled = enabled, divider = divider,
        modifier = modifier.toggleable(checked, enabled = enabled, role = Role.Switch,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onValueChange = onCheckedChange),
    ) { NSwitch(checked, onCheckedChange = null, enabled = enabled) }
}

/** A setting with one value out of a list, shown and chosen with an inline [NSelect]. */
@Composable
fun <T> NSelectRow(
    title: String,
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    divider: Boolean = true,
) {
    NSettingRow(title = title, description = description, modifier = modifier, divider = divider) {
        NSelect(options, value, onChange, style = NSelectStyle.Inline)
    }
}

/** A stepped value with its reading on the right; committed when the finger lifts. */
@Composable
fun NSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    format: (Float) -> String = { "%.2f×".format(it) },
    divider: Boolean = true,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(modifier.fillMaxWidth()) {
        NSettingRow(title = title, description = description, divider = false) {
            Text(format(draft), style = NotableTheme.type.label, color = NotableTheme.colors.ink)
        }
        NSlider(draft, valueRange, steps, onValueChange = { draft = it },
            onValueChangeFinished = { onValueChangeFinished(draft) },
            modifier = Modifier.padding(bottom = NotableSpace.md))
        if (divider) NDivider()
    }
}

// ───────────────────────────── Field ─────────────────────────────

/** A form field: label above, the control, then help or an error below. */
@Composable
fun NField(
    label: String,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NotableTheme.colors
    Column(modifier) {
        Text(label, style = NotableTheme.type.label, color = c.ink, modifier = Modifier.padding(bottom = NotableSpace.sm))
        content()
        when {
            error != null -> Row(Modifier.padding(top = NotableSpace.xs + NotableSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                NIcon(FeatherIcons.Info, size = 14.dp)
                Spacer(Modifier.width(NotableSpace.xs + NotableSpace.xxs))
                Text(error, style = NotableTheme.type.meta, color = c.ink)
            }
            help != null -> Text(help, style = NotableTheme.type.meta, color = c.secondary, modifier = Modifier.padding(top = NotableSpace.xs + NotableSpace.xxs))
        }
    }
}

// ───────────────────────────── Notice ─────────────────────────────

/** A quiet block of information: inset fill, an icon, text, optional actions. */
@Composable
fun NNotice(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = FeatherIcons.Info,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = NotableTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.inset, NotableShape.surface).padding(NotableSpace.lg),
        verticalAlignment = Alignment.Top,
    ) {
        NIcon(icon, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(NotableSpace.md))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = NotableTheme.type.label, color = c.ink, modifier = Modifier.padding(bottom = NotableSpace.xxs))
            Text(text, style = NotableTheme.type.body, color = c.ink)
            if (actions != null) Row(Modifier.padding(top = NotableSpace.md), horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm)) { actions() }
        }
    }
}

// ───────────────────────────── Tabs ─────────────────────────────

/** Equal-width text tabs; the current one in ink with a 2 dp underline, a hairline under all. */
@Composable
fun NTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = NotableTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp)
                        .selectable(on, interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(label, style = NotableTheme.type.label, color = if (on) c.ink else c.secondary,
                        modifier = Modifier.padding(bottom = NotableSpace.md), maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(NotableSize.strong).background(if (on) c.ink else c.paper))
                }
            }
        }
        NDivider()
    }
}

/** A tiny spacer helper for vertical rhythm in columns: `NGap(NotableSpace.xl)`. */
@Composable
fun NGap(size: androidx.compose.ui.unit.Dp) = Spacer(Modifier.size(size))
