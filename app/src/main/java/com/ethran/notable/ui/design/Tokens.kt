package com.ethran.notable.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design tokens: the only colors, type roles, spaces, shapes and sizes UI code should use.
 * See docs/plans/design-system.md. E-ink first: opaque surfaces, one hairline weight, no shadows,
 * no animation; contrast comes from ink and whitespace.
 */

@Immutable
data class NotableColors(
    /** Text, icons, filled controls, strong lines. */
    val ink: Color,
    /** Every surface. */
    val paper: Color,
    /** Quiet fills: fields at rest, notices, selected rows. */
    val inset: Color,
    /** Secondary text: descriptions, meta lines, unselected tabs. */
    val secondary: Color,
    /** Hairlines: dividers, control borders at rest, paper edges. */
    val rule: Color,
    /** Disabled text and borders. */
    val disabled: Color,
    val isDark: Boolean,
)

val LightNotableColors = NotableColors(
    ink = Color(0xFF191919),
    paper = Color(0xFFFFFFFF),
    inset = Color(0xFFF2F2F2),
    secondary = Color(0xFF5C5C5C),
    rule = Color(0xFFC4C4C4),
    disabled = Color(0xFFA3A3A3),
    isDark = false,
)

val DarkNotableColors = NotableColors(
    ink = Color(0xFFF5F5F5),
    paper = Color(0xFF191919),
    inset = Color(0xFF262626),
    secondary = Color(0xFFB0B0B0),
    rule = Color(0xFF4A4A4A),
    disabled = Color(0xFF6E6E6E),
    isDark = true,
)

/**
 * Type roles. Serif names places (screens, dialogs, sections); sans is for everything you read
 * or operate. Sentence case everywhere.
 */
@Immutable
data class NotableType(
    val display: TextStyle,
    /** Screen and dialog titles. */
    val title: TextStyle,
    /** Section headings inside a screen or dialog. */
    val heading: TextStyle,
    /** Running text and row titles. */
    val body: TextStyle,
    /** Text on controls: buttons, segments, tabs, selects. */
    val label: TextStyle,
    /** Secondary lines: descriptions, counts, dates, help. */
    val meta: TextStyle,
    /** Logs, tokens, code. */
    val mono: TextStyle,
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

val DefaultNotableType = NotableType(
    display = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 38.sp),
    title = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp),
    heading = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    meta = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
)

/** The spacing scale. Nothing in between. */
object NotableSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Left and right margin of a full screen. */
    val gutter = 32.dp
    /** Inside dialogs. */
    val dialog = 28.dp
}

object NotableShape {
    /** Buttons, fields, segments, selects, icon buttons. */
    val control: Shape = RoundedCornerShape(8.dp)
    /** Dialogs, menus, notices: control radius + their inner padding, so nested corners stay concentric. */
    val surface: Shape = RoundedCornerShape(14.dp)
    /** Badges and the switch track. */
    val pill: Shape = RoundedCornerShape(50)
    /** Pages and covers: paper has square corners. */
    val paper: Shape = RectangleShape
}

object NotableSize {
    /** Height of a regular control; also the minimum hit area. */
    val control = 44.dp
    /** Drawn height of a compact control in a dense bar; its hit area stays [control]. */
    val compact = 36.dp
    /** Icons inside buttons, fields and menu rows. */
    val icon = 20.dp
    /** Icons in icon buttons. */
    val iconButtonIcon = 22.dp
    /** Minimum height of a menu row. */
    val menuRow = 52.dp
    /** Minimum height of a settings row. */
    val row = 60.dp
    val hairline = 1.dp
    /** Focus and selection outlines. */
    val strong = 2.dp
    val topBar = 64.dp
}

val LocalNotableColors = staticCompositionLocalOf { LightNotableColors }
val LocalNotableType = staticCompositionLocalOf { DefaultNotableType }

/** Entry point for tokens: `NotableTheme.colors.ink`, `NotableTheme.type.body`, … */
object NotableTheme {
    val colors: NotableColors
        @Composable @ReadOnlyComposable get() = LocalNotableColors.current
    val type: NotableType
        @Composable @ReadOnlyComposable get() = LocalNotableType.current
    val space = NotableSpace
    val shape = NotableShape
    val size = NotableSize
}

/** A click without ripple or press animation: e-ink would flash for it. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.inkClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    if (onLongClick == null) clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = onClick,
    ) else combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
