package com.ethran.notable.editor.state

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.datastore.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedViewTest {

    @Test
    fun `fit to width shows the page at zoom 1 from its top left`() {
        assertEquals(1f to Offset.Zero, FixedView(FixedView.Fit.Width).target(1000, 1500, 1.414f))
    }

    @Test
    fun `fit to page zooms out until the page height fits the screen`() {
        // Landscape: a 1500 px wide A4 page is 2121 px tall; 1000 px of screen fits 1000 / 2121.
        val (zoom, scroll) = FixedView(FixedView.Fit.Page).target(1500, 1000, 1.414f)
        assertEquals(1000f / (1500f * 1.414f), zoom, 1e-4f)
        assertEquals(Offset.Zero, scroll)
    }

    @Test
    fun `fit to page never zooms in on a page shorter than the screen`() {
        assertEquals(1f, FixedView.fitPageZoom(1000, 1800, 1.414f))
    }

    @Test
    fun `fit to page of a degenerate screen or page stays at zoom 1`() {
        assertEquals(1f, FixedView.fitPageZoom(0, 1000, 1.4f))
        assertEquals(1f, FixedView.fitPageZoom(1000, 1000, 0f))
    }

    @Test
    fun `custom view keeps its zoom and position, never left of or above the page`() {
        assertEquals(2f to Offset(120f, 300f), FixedView(FixedView.Fit.Custom, 2f, 120f, 300f).target(1000, 1500, 1.4f))
        assertEquals(1.5f to Offset(0f, 0f), FixedView(FixedView.Fit.Custom, 1.5f, -5f, -1f).target(1000, 1500, 1.4f))
    }

    @Test
    fun `fixed views round-trip through the settings and old settings decode without any`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val settings = AppSettings(
            version = 1,
            fixedViews = mapOf(
                "book1" to FixedView(FixedView.Fit.Page),
                "book2" to FixedView(FixedView.Fit.Custom, 1.8f, 40f, 900f),
            ),
        )
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings.fixedViews, decoded.fixedViews)
        assertEquals(emptyMap<String, FixedView>(), json.decodeFromString(AppSettings.serializer(), """{"version":1}""").fixedViews)
    }
}
