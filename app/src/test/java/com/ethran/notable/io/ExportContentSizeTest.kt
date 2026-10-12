package com.ethran.notable.io

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** PNG export sizes a page like the PDF projection: content bottom + 50 px, at least one screen. */
class ExportContentSizeTest {
    private val screenWidth = 1860
    private val screenHeight = 2480

    @After
    fun resetSettings() = GlobalAppSettings.update(AppSettings(version = 1))

    private fun stroke(bottom: Float, right: Float) = Stroke(
        size = 3f, pen = Pen.BALLPEN,
        top = 0f, bottom = bottom, left = 0f, right = right,
        points = emptyList(), pageId = "p",
    )

    @Test
    fun `a page taller than the screen is its content plus the margin`() {
        val size = exportContentSize(listOf(stroke(3000f, 500f)), emptyList(), screenWidth, screenHeight)
        assertEquals(screenWidth to 3050, size)
    }

    @Test
    fun `an image below the strokes sets the height`() {
        val image = Image(x = 0, y = 2900, width = 100, height = 200, pageId = "p")
        val size = exportContentSize(listOf(stroke(3000f, 500f)), listOf(image), screenWidth, screenHeight)
        assertEquals(screenWidth to 3150, size)
    }

    @Test
    fun `a short page and an empty page are one screen`() {
        assertEquals(screenWidth to screenHeight, exportContentSize(listOf(stroke(100f, 100f)), emptyList(), screenWidth, screenHeight))
        assertEquals(screenWidth to screenHeight, exportContentSize(emptyList(), emptyList(), screenWidth, screenHeight))
    }
}
