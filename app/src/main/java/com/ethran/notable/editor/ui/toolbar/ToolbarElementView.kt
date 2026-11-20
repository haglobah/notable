package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ActionElement
import com.ethran.notable.editor.ui.toolbar.model.CustomElement
import com.ethran.notable.editor.ui.toolbar.model.CustomKind
import com.ethran.notable.editor.ui.toolbar.model.DividerElement
import com.ethran.notable.editor.ui.toolbar.model.EraserSubmenuSpec
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ModeElement
import com.ethran.notable.editor.ui.toolbar.model.PenElement
import com.ethran.notable.editor.ui.toolbar.model.ShapeElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElement
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.ui.components.ScaledPopup
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NSwitch
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.ethran.notable.ui.design.nSurface

/**
 * The single generic renderer for toolbar elements: draws the button (via [ToolbarButton]),
 * handles selected state, and opens the element's declared submenu. Stateless except
 * transient popup-open state; all real mutation flows through [ToolbarAction].
 *
 * [onPickImage] exists because the image picker's activity-result launcher is Compose
 * infrastructure owned by ToolbarContent, not something a [ToolbarAction] can express.
 */
@Composable
fun ToolbarElementView(
    element: ToolbarElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    onPickImage: () -> Unit,
) {
    when (element) {
        is DividerElement -> ToolbarVerticalDivider()

        is PenElement -> PenElementView(element, uiState, onAction)

        is ShapeElement ->
            // One shape (LINE) for now: the picker submenu is stubbed to a plain toggle,
            // matching the old LineToolbarButton (click again deselects back to Draw).
            ToolbarButton(
                isSelected = element.isSelected(uiState),
                onSelect = {
                    onAction(
                        ToolbarAction.ChangeMode(
                            if (element.isSelected(uiState)) Mode.Draw else Mode.Line
                        )
                    )
                },
                iconId = (element.icon as? IconRef.Drawable)?.resId,
                vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                contentDescription = element.contentDescription,
            )

        is ModeElement -> ModeElementView(element, uiState, onAction)

        is ActionElement ->
            ToolbarButton(
                isSelected = element.isSelected(uiState),
                onSelect = { onAction(element.action) },
                iconId = (element.icon as? IconRef.Drawable)?.resId,
                vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                contentDescription = element.contentDescription,
            )

        is CustomElement -> when (element.kind) {
            CustomKind.IMAGE_PICKER ->
                ToolbarButton(
                    iconId = (element.icon as? IconRef.Drawable)?.resId,
                    vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                    contentDescription = element.contentDescription,
                    onSelect = onPickImage,
                )

            CustomKind.PAGE_NAV ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(BUTTON_SIZE.dp)
                        .inkClickable { onAction(ToolbarAction.NavigateToPages) }
                        .padding(horizontal = NotableSpace.md)
                ) {
                    Text(
                        text = uiState.pageNumberInfo,
                        style = NotableTheme.type.label,
                        color = NotableTheme.colors.ink,
                        textAlign = TextAlign.Center
                    )
                }

            CustomKind.MENU ->
                Column {
                    ToolbarButton(
                        onSelect = { onAction(ToolbarAction.ToggleMenu) },
                        iconId = (element.icon as? IconRef.Drawable)?.resId,
                        contentDescription = element.contentDescription,
                    )
                    if (uiState.isMenuOpen) {
                        ToolbarMenu(
                            uiState = uiState,
                            onAction = onAction
                        )
                    }
                }
        }
    }
}

@Composable
private fun PenElementView(
    element: PenElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    var isStrokeMenuOpen by remember { mutableStateOf(false) }
    val isSelected = element.isSelected(uiState)
    val penSetting =
        uiState.penSettings[element.presetId] ?: element.setting.copy()

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) isStrokeMenuOpen = !isStrokeMenuOpen
                else onAction(ToolbarAction.ChangePen(element.presetId))
            },
            penColor = Color(penSetting.color),
            iconId = (element.icon as? IconRef.Drawable)?.resId,
            vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
            contentDescription = element.contentDescription,
        )

        if (isStrokeMenuOpen) {
            StrokeMenu(
                value = penSetting,
                onChange = { onAction(ToolbarAction.ChangePenSetting(element.presetId, it)) },
                onClose = { isStrokeMenuOpen = false },
                sizeOptions = element.submenu.sizeOptions,
                colorOptions = element.submenu.colorOptions,
            )
        }
    }
}

@Composable
private fun ModeElementView(
    element: ModeElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val isSelected = element.isSelected(uiState)
    val submenu = element.submenu

    // The eraser button reflects the active eraser type, not its static spec icon.
    val iconId =
        if (submenu is EraserSubmenuSpec) eraserIcon(uiState.eraser)
        else (element.icon as? IconRef.Drawable)?.resId

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected && submenu is EraserSubmenuSpec)
                    onAction(ToolbarAction.ToggleEraserManu(!uiState.isStrokeSelectionOpen))
                else if (!isSelected)
                    onAction(ToolbarAction.ChangeMode(element.mode))
            },
            iconId = iconId,
            vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
            contentDescription = element.contentDescription,
        )

        if (submenu is EraserSubmenuSpec && uiState.isStrokeSelectionOpen) {
            EraserSubmenu(
                spec = submenu,
                uiState = uiState,
                onAction = onAction,
            )
        }
    }
}

private fun eraserIcon(eraser: Eraser): Int =
    when (eraser) {
        Eraser.PEN -> R.drawable.eraser
        Eraser.SELECT -> R.drawable.eraser_select
    }

/** The eraser popup: eraser-type picker plus the global scribble-to-erase toggle. */
@Composable
private fun EraserSubmenu(
    spec: EraserSubmenuSpec,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val density = LocalDensity.current
    val c = NotableTheme.colors

    ScaledPopup(
        offset = with(density) {
            IntOffset(
                0,
                (BUTTON_SIZE + 6).dp.roundToPx()
            )
        },
        onDismissRequest = {
            onAction(ToolbarAction.ToggleEraserManu(false))
        },
        properties = PopupProperties(focusable = true),
        alignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                // Room for a bottom toolbar: the popup window is pushed up above it.
                .padding(bottom = (BUTTON_SIZE + 5).dp)
                .width(IntrinsicSize.Max)
                .nSurface(c)
                .padding(vertical = NotableSpace.sm)
        ) {
            Text(
                text = stringResource(R.string.toolbar_eraser),
                style = NotableTheme.type.meta,
                color = c.secondary,
                modifier = Modifier.padding(horizontal = NotableSpace.lg, vertical = NotableSpace.xs)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NotableSpace.md),
                horizontalArrangement = Arrangement.spacedBy(NotableSpace.xs, Alignment.CenterHorizontally)
            ) {
                spec.erasers.forEach { eraser ->
                    ToolbarButton(
                        iconId = eraserIcon(eraser),
                        isSelected = uiState.eraser == eraser,
                        onSelect = {
                            onAction(ToolbarAction.ChangeEraser(eraser))
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                }
            }

            NDivider(Modifier.padding(vertical = NotableSpace.sm))

            val initialState =
                GlobalAppSettings.current.scribbleToEraseEnabled

            var isChecked by remember {
                mutableStateOf(initialState)
            }
            val toggle: (Boolean) -> Unit = { checked ->
                isChecked = checked
                onAction(ToolbarAction.ToggleScribbleToErase(checked))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isChecked,
                        role = Role.Switch,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onValueChange = toggle,
                    )
                    .padding(start = NotableSpace.lg, end = NotableSpace.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.toolbar_scribble_to_erase_two_lined_short),
                    style = NotableTheme.type.body,
                    color = c.ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = NotableSpace.md)
                )
                NSwitch(checked = isChecked, onCheckedChange = null)
            }
        }
    }
}

/** The toolbar's one divider: a hairline in the rule color, inset from the bar's edges. */
@Composable
internal fun ToolbarVerticalDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .padding(vertical = NotableSpace.sm)
            .width(NotableSize.hairline)
            .background(NotableTheme.colors.rule)
    )
}
