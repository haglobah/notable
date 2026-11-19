package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import kotlinx.coroutines.launch

/**
 * Right-side fast scroller: an inset track and an ink thumb, no animation (e-ink).
 */
@Composable
fun BoxScope.FastScroller(
    modifier: Modifier = Modifier,
    state: LazyGridState,
    itemCount: Int,
    getVisibleIndex: () -> Int,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    trackWidth: Dp = 36.dp,
    thumbHeight: Dp = 56.dp,
    quantization: Int = 2
) {
    val scope = rememberCoroutineScope()
    val c = NotableTheme.colors
    val density = LocalDensity.current

    var dragIndex by remember { mutableIntStateOf(getVisibleIndex()) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val fraction: Float =
        if (itemCount <= 1) 0f else (getVisibleIndex().toFloat() / (itemCount - 1).toFloat()).coerceIn(
            0f, 1f
        )
    val thumbOffsetYPx: Float = (containerHeightPx - thumbHeightPx).coerceAtLeast(0f) * fraction

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(trackWidth)
            .padding(vertical = NotableSpace.sm)
            .background(c.inset, NotableShape.control)
            .onGloballyPositioned { coords ->
                containerHeightPx = coords.size.height
            }
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }) { change, _ ->
                    change.consume()
                    val localY = change.position.y.coerceIn(0f, containerHeightPx.toFloat())
                    val frac =
                        if (containerHeightPx == 0) 0f else (localY / containerHeightPx.toFloat())
                    val rawIndex = (frac * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
                    val quantized = (rawIndex / quantization) * quantization
                    if (quantized != dragIndex) {
                        dragIndex = quantized
                        scope.launch { state.scrollToItem(dragIndex) }
                    }
                }
            }
            .align(Alignment.CenterEnd)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .height(thumbHeight)
                .fillMaxWidth()
                .offset { IntOffset(0, thumbOffsetYPx.toInt()) }
                .clip(NotableShape.control)
                .background(c.ink, NotableShape.control)
        )
    }
}