package com.ethran.notable.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.shipbook.shipbooksdk.ShipBook
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val log = ShipBook.getLogger("EdgeSwipeGesture")

/**
 * Where a one-finger edge swipe starts. reMarkable uses the top and bottom edges; on a Boox those
 * belong to the system's own swipes, so the side edges (whose system swipes can be turned off) carry
 * them instead.
 */
enum class EdgeSwipe {
    /** In from the upper half of the left edge: recent notebooks. */
    LeftUpper,

    /** In from the lower half of the left edge: back to the library. */
    LeftLower,

    /** In from the right edge: quick browse through the pages. */
    Right,
}

/**
 * How far from the side edge a swipe may start. Narrow, so a page-turn swipe started a little in
 * from the edge still turns the page.
 */
private val EDGE_ZONE = 40.dp

/** Inward travel at which the swipe is claimed from the toolbar and canvas below. */
private val EDGE_CLAIM = 24.dp

/** Inward travel the swipe needs by the time the finger lifts. */
private val EDGE_SWIPE = 56.dp

/** The edge zone [down] lies in, or null. The left edge is split at half height. */
fun edgeSwipeZone(down: Offset, size: IntSize, edgePx: Float): EdgeSwipe? = when {
    down.x < edgePx -> if (down.y < size.height / 2f) EdgeSwipe.LeftUpper else EdgeSwipe.LeftLower
    down.x > size.width - edgePx -> EdgeSwipe.Right
    else -> null
}

/** How far [travel] goes away from [edge], into the screen; negative when toward it. */
fun inwardTravel(edge: EdgeSwipe, travel: Offset): Float = when (edge) {
    EdgeSwipe.LeftUpper, EdgeSwipe.LeftLower -> travel.x
    EdgeSwipe.Right -> -travel.x
}

/** Whether [travel] goes into the screen by at least [minPx], more than it goes up or down. */
fun isInwardSwipe(edge: EdgeSwipe, travel: Offset, minPx: Float): Boolean {
    val inward = inwardTravel(edge, travel)
    return inward >= minPx && inward > abs(travel.y)
}

/**
 * One finger swiping in from the left or right edge. Watches touches in the Initial pass, before
 * the toolbar and the editor's gesture receiver, without consuming them; once the finger has clearly
 * moved inward it consumes the rest of the gesture, so it doesn't also turn the page (a swipe in
 * from the right is a leftward swipe) or click a toolbar button under the finger. A second finger
 * leaves the gesture to the others. Only swipes from [edges] are claimed; the others pass untouched.
 */
fun Modifier.edgeSwipeGestures(
    enabled: Boolean,
    edges: Set<EdgeSwipe> = EdgeSwipe.entries.toSet(),
    onSwipe: (EdgeSwipe) -> Unit,
): Modifier = this.pointerInput(enabled, edges) {
    if (!enabled) return@pointerInput
    val edgePx = EDGE_ZONE.toPx()
    val claimPx = EDGE_CLAIM.toPx()
    val swipePx = EDGE_SWIPE.toPx()
    awaitEachGesture {
        try {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            val edge = edgeSwipeZone(down.position, size, edgePx)?.takeIf { it in edges }
                ?: return@awaitEachGesture
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!claimed && event.changes.count { it.type == PointerType.Touch && it.pressed } > 1)
                    return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                val travel = change.position - down.position
                if (!claimed && isInwardSwipe(edge, travel, claimPx)) {
                    log.d("Edge swipe claimed: $edge")
                    claimed = true
                }
                if (claimed) event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
                if (!change.pressed) {
                    if (claimed && isInwardSwipe(edge, travel, swipePx)) onSwipe(edge)
                    return@awaitEachGesture
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("Unexpected error in edge swipe", e)
        }
    }
}
