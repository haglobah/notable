package com.ethran.notable.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.Paper
import com.ethran.notable.ui.theme.PaperLine

/** Flat, high-contrast controls without shadows, suitable for an e-ink surface. */
@Composable
fun PaperAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, if (primary && enabled) Ink else PaperLine),
        color = if (primary && enabled) Ink else Paper,
        contentColor = if (!enabled) Ink.copy(alpha = 0.38f) else if (primary) Paper else Ink,
    ) {
        CompositionLocalProvider(LocalContentAlpha provides if (enabled) 1f else ContentAlpha.disabled) {
            Row(
                Modifier.heightIn(min = 48.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick
                ).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, style = MaterialTheme.typography.button)
            }
        }
    }
}

@Composable
fun PaperIconAction(
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(48.dp).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        role = Role.Button, onClick = onClick
    ), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = Ink, modifier = Modifier.size(22.dp))
    }
}
