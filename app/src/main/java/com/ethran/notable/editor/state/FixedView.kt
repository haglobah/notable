package com.ethran.notable.editor.state

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable

/**
 * A notebook's fixed view, as reMarkable's "Adjust view": one zoom and position for every page of
 * the notebook, so turning a page keeps the view instead of each page remembering its own. Pinching
 * still zooms the page in front of you; turning the page (or reset view) returns to this view.
 *
 * Kept per device in the app settings, keyed by notebook id: a view fits this screen.
 */
@Serializable
data class FixedView(
    val fit: Fit,
    /** [Fit.Custom] only: the zoom and the top-left corner in page coordinates. */
    val zoom: Float = 1f,
    val scrollX: Float = 0f,
    val scrollY: Float = 0f,
) {
    enum class Fit { Width, Page, Custom }

    /**
     * The zoom and scroll to show a page at. [viewWidth] and [viewHeight] are the screen in px;
     * [pageAspect] is the page's height over its width (the background's, or A4).
     */
    fun target(viewWidth: Int, viewHeight: Int, pageAspect: Float): Pair<Float, Offset> = when (fit) {
        Fit.Width -> 1f to Offset.Zero
        Fit.Page -> fitPageZoom(viewWidth, viewHeight, pageAspect) to Offset.Zero
        Fit.Custom -> zoom to Offset(scrollX.coerceAtLeast(0f), scrollY.coerceAtLeast(0f))
    }

    companion object {
        /** A4's height over its width, for pages without a background to measure. */
        const val A4_ASPECT = 842f / 595f

        /**
         * The zoom at which the whole page fits the screen. A page drawn at zoom 1 is as wide as
         * the screen, so it fits once its height does; never zooms in.
         */
        fun fitPageZoom(viewWidth: Int, viewHeight: Int, pageAspect: Float): Float {
            if (viewWidth <= 0 || viewHeight <= 0 || pageAspect <= 0f) return 1f
            return (viewHeight / (viewWidth * pageAspect)).coerceAtMost(1f)
        }
    }
}
