package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.LocalContentColor
import androidx.compose.material.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.remember
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.nSurface

// Dialog and Popup open in their own window, which resets LocalDensity to the device density.
// These wrappers carry the caller's (UI-scaled) density into the window.

@Composable
fun ScaledDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/**
 * Material AlertDialog opens its own window internally, so it is rebuilt here on [ScaledDialog],
 * with the look of [com.ethran.notable.ui.design.NDialog]: a framed paper surface, a serif title,
 * body text, actions at the bottom right (dismiss first, confirm last).
 */
@Composable
fun ScaledAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    ScaledDialog(onDismissRequest = onDismissRequest, properties = properties) {
        val c = NotableTheme.colors
        Column(modifier.widthIn(max = 480.dp).nSurface(c).padding(NotableSpace.dialog)) {
            CompositionLocalProvider(LocalContentColor provides c.ink) {
                if (title != null) {
                    ProvideTextStyle(NotableTheme.type.title, title)
                    Spacer(Modifier.height(NotableSpace.xl))
                }
                if (text != null) {
                    ProvideTextStyle(NotableTheme.type.body, text)
                    Spacer(Modifier.height(NotableSpace.xl))
                }
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun ScaledPopup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/**
 * Places a popup right under its anchor (the layout that holds the [ScaledAnchoredPopup] call),
 * left-aligned with it, or above it when there's no room below; always inside the window. So a
 * menu opens next to the button that opened it, where the hand already is.
 */
private class BelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** A popup anchored to its caller's layout (see [BelowAnchor]), without animation, in the caller's density. */
@Composable
fun ScaledAnchoredPopup(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { BelowAnchor(gap) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}
