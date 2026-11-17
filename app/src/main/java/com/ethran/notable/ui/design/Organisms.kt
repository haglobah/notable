package com.ethran.notable.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.components.ScaledAnchoredPopup
import com.ethran.notable.ui.components.ScaledDialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

// ───────────────────────────── Surface ─────────────────────────────

/** A raised surface on e-ink: paper, a 1 dp ink outline, the surface radius. No shadow. */
fun Modifier.nSurface(colors: NotableColors): Modifier =
    this.clip(NotableShape.surface).background(colors.paper, NotableShape.surface)
        .border(NotableSize.hairline, colors.ink, NotableShape.surface)

// ───────────────────────────── Menu ─────────────────────────────

/**
 * A menu under the composable that calls it (put both in one Box), flipping above when there is no
 * room. Rows are [NMenuItem], [NMenuCheckItem], [NMenuDivider], [NMenuHeader]. Scrolls past 60 % of
 * the screen.
 */
@Composable
fun NMenu(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 240.dp,
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val colors = NotableTheme.colors
    ScaledAnchoredPopup(onDismissRequest = onDismiss) {
        Column(
            modifier.widthIn(min = minWidth, max = maxWidth).width(IntrinsicSize.Max).heightIn(max = maxHeight)
                .nSurface(colors)
                .verticalScroll(rememberScrollState())
                .padding(vertical = NotableSpace.xs + NotableSpace.xxs),
            content = content,
        )
    }
}

/** [NMenu] for long lists (facets, recent notebooks): rows are composed lazily. */
@Composable
fun NLazyMenu(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 280.dp,
    maxWidth: Dp = 480.dp,
    content: LazyListScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val colors = NotableTheme.colors
    ScaledAnchoredPopup(onDismissRequest = onDismiss) {
        Box(modifier.widthIn(min = minWidth, max = maxWidth).heightIn(max = maxHeight).nSurface(colors)) {
            LazyColumn(Modifier.autoEInkAnimationOnScroll().padding(vertical = NotableSpace.xs + NotableSpace.xxs), content = content)
        }
    }
}

/** A short explanation anchored under its button: a heading and free content on a framed surface. */
@Composable
fun NPopover(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, maxWidth: Dp = 560.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = NotableTheme.colors
    ScaledAnchoredPopup(onDismissRequest = onDismiss) {
        Column(modifier.widthIn(max = maxWidth).nSurface(c).padding(NotableSpace.xl)) {
            Text(title, style = NotableTheme.type.heading, color = c.ink)
            Spacer(Modifier.height(NotableSpace.sm))
            content()
        }
    }
}

// ───────────────────────────── Top bar ─────────────────────────────

/**
 * The bar at the top of a screen: back, the screen's serif title, actions on the right, an ink
 * line under it.
 */
@Composable
fun NTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Back",
    /** Small text after the title, e.g. the version. */
    meta: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = NotableTheme.colors
    Column(modifier.fillMaxWidth().background(c.paper)) {
        Row(
            Modifier.fillMaxWidth().height(NotableSize.topBar)
                .padding(start = if (onBack != null) NotableSpace.md else NotableSpace.gutter, end = NotableSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                NIconButton(FeatherIcons.ArrowLeft, backDescription, onBack)
                Spacer(Modifier.width(NotableSpace.sm))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = NotableTheme.type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (meta != null) Text(meta, style = NotableTheme.type.meta, color = c.secondary, maxLines = 1,
                    modifier = Modifier.padding(start = NotableSpace.md, top = NotableSpace.xs))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm), content = actions)
        }
        NDivider(strong = true)
    }
}

// ───────────────────────────── Dialog ─────────────────────────────

/**
 * The dialog: a framed paper surface, a serif title (with an optional action beside it), a
 * scrolling body, and actions at the bottom right (secondary first, the main action last).
 */
@Composable
fun NDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 560.dp,
    /** Beside the title, e.g. a "Done" button for dialogs that save as you go. */
    titleAction: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NotableTheme.colors
    ScaledDialog(onDismissRequest = onDismiss, properties = properties) {
        Column(
            modifier.padding(NotableSpace.xl).widthIn(max = maxWidth).fillMaxWidth().nSurface(c)
                .padding(NotableSpace.dialog),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = NotableTheme.type.title, color = c.ink, modifier = Modifier.weight(1f))
                if (titleAction != null) {
                    Spacer(Modifier.width(NotableSpace.lg))
                    titleAction()
                }
            }
            Spacer(Modifier.height(NotableSpace.xl))
            Column(
                Modifier.weight(1f, fill = false).then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                content = content,
            )
            if (actions != null) {
                Spacer(Modifier.height(NotableSpace.xl))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/** A question with a message, Cancel and one confirming verb. [destructive] for deletes. */
@Composable
fun NConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelText: String = "Cancel",
    destructive: Boolean = false,
) {
    NDialog(
        title = title, onDismiss = onDismiss, maxWidth = 480.dp,
        actions = {
            NButton(cancelText, onDismiss, style = NButtonStyle.Quiet)
            NButton(confirmText, onConfirm, style = if (destructive) NButtonStyle.Danger else NButtonStyle.Primary)
        },
    ) {
        Text(message, style = NotableTheme.type.body, color = NotableTheme.colors.ink)
    }
}

// ───────────────────────────── Sections and states ─────────────────────────────

/** A titled group inside a screen: serif heading, an optional description, then its rows. */
@Composable
fun NSection(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NotableTheme.colors
    Column(modifier.fillMaxWidth().padding(top = NotableSpace.xl)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = NotableTheme.type.heading, color = c.ink, modifier = Modifier.weight(1f))
                if (trailing != null) trailing()
            }
            if (description != null) Text(description, style = NotableTheme.type.meta, color = c.secondary,
                modifier = Modifier.padding(top = NotableSpace.xs))
            Spacer(Modifier.height(NotableSpace.sm))
        }
        content()
    }
}

/** What an empty place says, and the one thing to do about it. */
@Composable
fun NEmptyState(title: String, modifier: Modifier = Modifier, message: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = NotableTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = NotableSpace.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = NotableTheme.type.heading, color = c.ink)
        if (message != null) Text(message, style = NotableTheme.type.body, color = c.secondary,
            modifier = Modifier.padding(top = NotableSpace.sm).widthIn(max = 420.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(NotableSpace.xl))
            action()
        }
    }
}

/** A block of log or code: inset, monospaced, framed. */
@Composable
fun NCodeBlock(text: String, modifier: Modifier = Modifier, maxHeight: Dp = 320.dp) {
    val c = NotableTheme.colors
    Box(
        modifier.fillMaxWidth().heightIn(max = maxHeight).background(c.inset, NotableShape.surface)
            .verticalScroll(rememberScrollState()).padding(NotableSpace.lg)
    ) {
        Text(text, style = NotableTheme.type.mono, color = c.ink)
    }
}
