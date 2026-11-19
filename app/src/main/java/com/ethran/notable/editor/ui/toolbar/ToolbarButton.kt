package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.design.nColorMark
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable

/**
 * A tool button in the editor toolbar and its popups. Selected: filled with ink at the control
 * radius, the icon in paper (the same look as a selected NIconButton). A pen's color is user
 * data and shows as a small ringed dot in the bottom-right corner, never as the button fill.
 *
 * Sizes stay what the toolbar layout relies on: 2 + 5 dp around a 24 dp icon (38 dp, clipped to
 * BUTTON_SIZE by the bar). The color dot is drawn, not laid out, so a pen button costs no extra
 * layout nodes on the hot path.
 */
@Composable
fun ToolbarButton(
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    iconId: Int? = null,
    vectorIcon: ImageVector? = null,
    imageVector: ImageVector? = null,
    text: String? = null,
    penColor: Color? = null,
    contentDescription: String = ""
) {
    val c = NotableTheme.colors
    val tint = if (isSelected) c.paper else c.ink

    Box(
        Modifier
            .then(modifier)
            .inkClickable(onClick = onSelect)
            .padding(NotableSpace.xxs)
            .then(if (isSelected) Modifier.background(c.ink, NotableShape.control) else Modifier)
            .nColorMark(penColor, c)
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            iconId != null -> Icon(painterResource(iconId), contentDescription, tint = tint)
            vectorIcon != null -> Icon(vectorIcon, contentDescription, tint = tint)
            imageVector != null -> Icon(imageVector, contentDescription, tint = tint)
            text != null -> Text(text, style = NotableTheme.type.label, color = tint)
        }
    }
}
