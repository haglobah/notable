package com.ethran.notable.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import kotlin.math.roundToInt

// ───────────────────────────── Icon ─────────────────────────────

/** An icon at the system size, in ink. Feather icons in app chrome. */
@Composable
fun NIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = NotableSize.icon,
    tint: Color = NotableTheme.colors.ink,
) {
    Icon(icon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

// ───────────────────────────── Buttons ─────────────────────────────

enum class NButtonStyle {
    /** The one main action of a view: filled ink. */
    Primary,
    /** Other actions: outlined. */
    Secondary,
    /** Low-emphasis actions in bars and lists: text only. */
    Quiet,
    /** Deletes or discards: outlined, set apart, always with a verb ("Delete notebook"). */
    Danger,
}

enum class NControlSize { Regular, Compact }

private fun NControlSize.drawn() = if (this == NControlSize.Regular) NotableSize.control else NotableSize.compact

/**
 * The button. One shape, one height per size, one icon size. The drawn control of a compact
 * button is 36 dp, its hit area still 44 dp.
 */
@Composable
fun NButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NButtonStyle = NButtonStyle.Secondary,
    size: NControlSize = NControlSize.Regular,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    /** Shows a selected (inverted) state, for buttons that stand for an active filter. */
    selected: Boolean = false,
    /** A second action on long press (e.g. a folder's settings). */
    onLongClick: (() -> Unit)? = null,
) {
    val c = NotableTheme.colors
    val filled = enabled && (style == NButtonStyle.Primary || selected)
    val background = when {
        filled -> c.ink
        !enabled && style == NButtonStyle.Primary -> c.inset
        else -> c.paper
    }
    val content = when {
        !enabled -> c.disabled
        filled -> c.paper
        else -> c.ink
    }
    val border = when {
        style == NButtonStyle.Quiet && !selected -> null
        !enabled -> BorderStroke(NotableSize.hairline, c.rule)
        else -> BorderStroke(NotableSize.hairline, c.ink)
    }
    Box(
        modifier
            .heightIn(min = NotableSize.control)
            .inkClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(size.drawn())
                .clip(NotableShape.control)
                .background(background, NotableShape.control)
                .then(if (border != null) Modifier.border(border, NotableShape.control) else Modifier)
                .padding(horizontal = if (style == NButtonStyle.Quiet && !selected) NotableSpace.md else NotableSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                NIcon(icon, tint = content, size = if (size == NControlSize.Regular) NotableSize.icon else 18.dp)
                Spacer(Modifier.width(NotableSpace.sm))
            }
            Text(text, style = NotableTheme.type.label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailingIcon != null) {
                Spacer(Modifier.width(NotableSpace.xs + NotableSpace.xxs))
                NIcon(trailingIcon, tint = content, size = 16.dp)
            }
        }
    }
}

/**
 * An icon-only button: 44 dp hit area, 22 dp icon. [selected] fills it with ink (a tool that is on);
 * [outlined] gives it the button border, for icon buttons that sit in a row of buttons.
 */
@Composable
fun NIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    outlined: Boolean = false,
    enabled: Boolean = true,
    size: NControlSize = NControlSize.Regular,
    /** A small ink dot in the corner: something new behind this button (e.g. an update). */
    badge: Boolean = false,
) {
    val c = NotableTheme.colors
    val tint = when {
        !enabled -> c.disabled
        selected -> c.paper
        else -> c.ink
    }
    Box(
        modifier
            .size(NotableSize.control)
            .inkClickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size.drawn())
                .then(if (selected) Modifier.background(c.ink, NotableShape.control) else Modifier)
                .then(
                    if (outlined && !selected) Modifier.border(NotableSize.hairline, if (enabled) c.ink else c.rule, NotableShape.control)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            NIcon(icon, tint = tint, size = if (size == NControlSize.Regular) NotableSize.iconButtonIcon else NotableSize.icon)
        }
        if (badge) Box(
            Modifier.align(Alignment.TopEnd).padding(NotableSpace.sm).size(NotableSpace.sm)
                .background(c.ink, CircleShape).border(1.5.dp, c.paper, CircleShape)
        )
    }
}

/** An inline text link: underlined label text, 44 dp hit area. */
@Composable
fun NLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = NotableTheme.colors
    Box(
        modifier.heightIn(min = NotableSize.control).inkClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = NotableSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = NotableTheme.type.label.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            color = if (enabled) c.ink else c.disabled,
        )
    }
}

// ───────────────────────────── Selection controls ─────────────────────────────

private val SwitchWidth = 46.dp
private val SwitchHeight = 26.dp

/**
 * On: an ink track with a paper knob on the right. Off: an outlined track with an ink knob on
 * the left. No text, no animation.
 */
@Composable
fun NSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = NotableTheme.colors
    val line = if (enabled) c.ink else c.disabled
    val toggle = if (onCheckedChange != null) Modifier.toggleable(
        value = checked, enabled = enabled, role = Role.Switch,
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onValueChange = onCheckedChange,
    ) else Modifier
    Box(modifier.size(width = SwitchWidth + 4.dp, height = NotableSize.control).then(toggle), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(SwitchWidth, SwitchHeight)
                .background(if (checked) line else c.paper, NotableShape.pill)
                .border(NotableSize.hairline, line, NotableShape.pill),
            contentAlignment = Alignment.CenterStart,
        ) {
            val knob = if (checked) 20.dp else 16.dp
            val x = if (checked) SwitchWidth - knob - 3.dp else 5.dp
            Box(Modifier.offset(x = x).size(knob).background(if (checked) c.paper else line, CircleShape))
        }
    }
}

/** A square check box, 22 dp drawn, 44 dp hit area when it is clickable by itself. */
@Composable
fun NCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = NotableTheme.colors
    val line = if (enabled) c.ink else c.disabled
    val toggle = if (onCheckedChange != null) Modifier.size(NotableSize.control).toggleable(
        value = checked, enabled = enabled, role = Role.Checkbox,
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onValueChange = onCheckedChange,
    ) else Modifier
    Box(modifier.then(toggle), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp)
                .background(if (checked) line else c.paper, RoundedCorner5)
                .border(1.5.dp, line, RoundedCorner5),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) NIcon(FeatherIcons.Check, tint = c.paper, size = 16.dp)
        }
    }
}

private val RoundedCorner5 = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)

/** A radio mark: a ring, filled with a dot when selected. */
@Composable
fun NRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = NotableTheme.colors
    val line = if (enabled) c.ink else c.disabled
    val select = if (onClick != null) Modifier.size(NotableSize.control).selectable(
        selected = selected, enabled = enabled, role = Role.RadioButton,
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
    ) else Modifier
    Box(modifier.then(select), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).border(1.5.dp, line, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(line, CircleShape))
        }
    }
}

// ───────────────────────────── Lines and marks ─────────────────────────────

/**
 * A pen's color as a small dot in the bottom-right corner of a tool: ringed in paper so it reads
 * on an ink fill, outlined in ink so light colors read on paper. Pen colors are user data, never
 * a token.
 */
fun Modifier.nColorMark(color: Color?, colors: NotableColors, radius: Dp = 4.dp, inset: Dp = 7.dp): Modifier =
    if (color == null) this else this.drawWithContent {
        drawContent()
        val r = radius.toPx()
        val center = Offset(size.width - inset.toPx(), size.height - inset.toPx())
        drawCircle(colors.paper, radius = r + 1.5.dp.toPx(), center = center)
        drawCircle(color, radius = r, center = center)
        drawCircle(colors.ink, radius = r, center = center, style = Stroke(NotableSize.hairline.toPx()))
    }

/** The hairline. [strong] uses ink, for the one line that separates a bar from content. */
@Composable
fun NDivider(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(modifier.fillMaxWidth().height(NotableSize.hairline).background(if (strong) NotableTheme.colors.ink else NotableTheme.colors.rule))
}

/** A small count or status mark: "3", "PDF", "Finished". [filled] inverts it. */
@Composable
fun NBadge(text: String, modifier: Modifier = Modifier, filled: Boolean = false, icon: ImageVector? = null) {
    val c = NotableTheme.colors
    Row(
        modifier.height(22.dp)
            .background(if (filled) c.ink else c.paper, NotableShape.pill)
            .border(NotableSize.hairline, c.ink, NotableShape.pill)
            .padding(horizontal = NotableSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NIcon(icon, tint = if (filled) c.paper else c.ink, size = 12.dp)
            Spacer(Modifier.width(NotableSpace.xs))
        }
        Text(text, style = NotableTheme.type.meta.copy(lineHeight = NotableTheme.type.meta.fontSize), color = if (filled) c.paper else c.ink, maxLines = 1)
    }
}

// ───────────────────────────── Text field ─────────────────────────────

/**
 * The text field: 44 dp, hairline outline at rest, a 2 dp ink outline while focused. Labels go
 * above it ([NField]), not inside.
 */
@Composable
fun NTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = NotableTheme.type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val c = NotableTheme.colors
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { if (placeholder != null) contentDescription = placeholder },
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle.copy(color = if (enabled) c.ink else c.disabled),
        cursorBrush = SolidColor(c.ink),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = NotableSize.control)
                    .background(c.paper, NotableShape.control)
                    .border(
                        if (focused) NotableSize.strong else NotableSize.hairline,
                        if (focused) c.ink else if (enabled) c.secondary else c.rule,
                        NotableShape.control,
                    )
                    .padding(start = if (leadingIcon != null) NotableSpace.md else 14.dp, end = if (trailing != null) NotableSpace.xxs else 14.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (leadingIcon != null) {
                    NIcon(leadingIcon, tint = c.secondary, modifier = if (singleLine) Modifier else Modifier.padding(top = NotableSpace.md))
                    Spacer(Modifier.width(NotableSpace.sm + NotableSpace.xxs))
                }
                Box(Modifier.weight(1f).padding(vertical = if (singleLine) 0.dp else NotableSpace.md)) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = c.secondary, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

// ───────────────────────────── Slider ─────────────────────────────

private val SliderHeight = 36.dp
private val SliderTrackHeight = 6.dp
private val SliderThumb = 22.dp

/**
 * A stepped slider: an outlined track filled up to a round thumb, a tick per step. No animation.
 * A tap jumps to the nearest step; dragging moves step by step. [onValueChangeFinished] fires
 * after a tap or at the end of a drag.
 *
 * @param steps number of values between the two ends, as in Material's Slider.
 */
@Composable
fun NSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val intervals = steps + 1
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / span * intervals)
        .roundToInt().coerceIn(0, intervals).toFloat() / intervals

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val ink = NotableTheme.colors.ink
    val paper = NotableTheme.colors.paper
    val rule = NotableTheme.colors.rule

    fun PointerInputScope.valueAt(x: Float): Float {
        val half = SliderThumb.toPx() / 2
        val usable = (size.width - 2 * half).coerceAtLeast(1f)
        val index = ((x - half) / usable * intervals).roundToInt().coerceIn(0, intervals)
        return valueRange.start + index * span / intervals
    }

    Box(
        modifier.fillMaxWidth().height(SliderHeight)
            .pointerInput(valueRange, steps) {
                detectTapGestures { offset ->
                    currentOnValueChange(valueAt(offset.x))
                    currentOnValueChangeFinished()
                }
            }
            .pointerInput(valueRange, steps) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> currentOnValueChange(valueAt(offset.x)) },
                    onDragEnd = { currentOnValueChangeFinished() },
                    onDragCancel = { currentOnValueChangeFinished() },
                ) { change, _ ->
                    change.consume()
                    currentOnValueChange(valueAt(change.position.x))
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val thumb = SliderThumb.toPx()
            val half = thumb / 2
            val usable = size.width - thumb
            val centerY = size.height / 2
            val trackHeight = SliderTrackHeight.toPx()
            val trackTop = centerY - trackHeight / 2
            val line = 1.dp.toPx()
            val corner = CornerRadius(trackHeight / 2)
            val thumbX = half + usable * fraction

            for (i in 0..intervals) {
                val x = half + usable * i / intervals
                drawCircle(if (x <= thumbX) ink else rule, radius = 1.5.dp.toPx(), center = Offset(x, trackTop + trackHeight + 7.dp.toPx()))
            }
            drawRoundRect(rule, Offset(half, trackTop), Size(usable, trackHeight), corner)
            drawRoundRect(ink, Offset(half, trackTop), Size(thumbX - half, trackHeight), corner)
            drawCircle(paper, radius = half, center = Offset(thumbX, centerY))
            drawCircle(ink, radius = half - line, center = Offset(thumbX, centerY), style = Stroke(2.dp.toPx()))
        }
    }
}
