package com.ethran.notable.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Anchor


@Composable
fun Anchor(
    onClose: () -> Unit,
    verticalOffsetPercent: Float = 0.10f // ~10% from top
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val yOffset = screenHeightDp * verticalOffsetPercent

    // The tab is as tall as a control, so it is a full 44 dp target.
    val circleDiameter: Dp = NotableSize.control
    val extensionWidthDp: Dp = 10.dp
    val totalWidth = extensionWidthDp + circleDiameter / 2

    Box(
        modifier = Modifier
            .offset(x = 0.dp, y = yOffset)
            .size(width = totalWidth, height = circleDiameter)
            .semantics { contentDescription = "Return to previous page" }
            .inkClickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        DrawAnchorLabel(totalWidth, circleDiameter, extensionWidthDp)
    }
}

@Composable
private fun DrawAnchorLabel(totalWidth: Dp, circleDiameter: Dp, extensionWidthDp: Dp) {
    val c = NotableTheme.colors
    Canvas(modifier = Modifier.size(width = totalWidth, height = circleDiameter)) {

        val extW = extensionWidthDp.toPx()
        val circleDiaPx = circleDiameter.toPx()

        // Arc bounding rect: starts after extension; width = circle diameter; we use left half via arc angles.
        val arcRect = Rect(
            left = 0f,
            top = 0f,
            right = circleDiaPx,
            bottom = circleDiaPx
        )

        // Build path: start at top-left (0,0), go to top of arcRect, add half-circle arc (left side),
        // then back down and close forming the extension rectangle + semicircle.
        val path = Path().apply {
            moveTo(0f, 0f)                // top-left of extension
            lineTo(extW, 0f)             // top-right of extension (start of arc)
            // Draw half-circle: we want the LEFT half (facing outward) -> start at 270°, sweep 180°
            // Because the bounding circle sits to the right, using 270 -> 180 sweeps from top through left to bottom
            arcTo(
                rect = arcRect,
                startAngleDegrees = 270f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            lineTo(0f, circleDiaPx)    // bottom-left of extension
            close()
        }

        drawPath(
            path = path,
            color = c.ink
        )
    }

    // Place anchor icon roughly centered over the semicircle portion (shift right a bit)
    NIcon(
        FeatherIcons.Anchor,
        contentDescription = null, // the tab carries the description
        tint = c.paper,
        size = circleDiameter * 0.4f,
        modifier = Modifier
            .offset(x = (extensionWidthDp / 2)) // nudge right so it’s visually centered in curved area
    )
}