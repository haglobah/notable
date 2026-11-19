package com.ethran.notable.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSwipeGestureTest {

    private val screen = IntSize(1200, 1800)
    private val edge = 40f

    @Test
    fun `the left edge splits at half height into recents above and back below`() {
        assertEquals(EdgeSwipe.LeftUpper, edgeSwipeZone(Offset(5f, 10f), screen, edge))
        assertEquals(EdgeSwipe.LeftUpper, edgeSwipeZone(Offset(5f, 899f), screen, edge))
        assertEquals(EdgeSwipe.LeftLower, edgeSwipeZone(Offset(5f, 900f), screen, edge))
        assertEquals(EdgeSwipe.LeftLower, edgeSwipeZone(Offset(39f, 1790f), screen, edge))
    }

    @Test
    fun `a touch along the right edge starts a right swipe`() {
        assertEquals(EdgeSwipe.Right, edgeSwipeZone(Offset(1195f, 900f), screen, edge))
        assertEquals(EdgeSwipe.Right, edgeSwipeZone(Offset(1161f, 30f), screen, edge))
    }

    @Test
    fun `a touch a little in from the edges starts none, so page turns stay page turns`() {
        assertNull(edgeSwipeZone(Offset(41f, 900f), screen, edge))
        assertNull(edgeSwipeZone(Offset(1159f, 900f), screen, edge))
        assertNull(edgeSwipeZone(Offset(600f, 5f), screen, edge))
        assertNull(edgeSwipeZone(Offset(600f, 1795f), screen, edge))
    }

    @Test
    fun `only travel into the screen, more than up or down, is a swipe`() {
        assertTrue(isInwardSwipe(EdgeSwipe.LeftUpper, Offset(60f, 10f), 56f))
        assertTrue(isInwardSwipe(EdgeSwipe.Right, Offset(-60f, -30f), 56f))
        // Toward the edge, too short, or mostly vertical (a scroll along the edge).
        assertFalse(isInwardSwipe(EdgeSwipe.LeftUpper, Offset(-80f, 0f), 56f))
        assertFalse(isInwardSwipe(EdgeSwipe.Right, Offset(-40f, 0f), 56f))
        assertFalse(isInwardSwipe(EdgeSwipe.Right, Offset(-80f, 200f), 56f))
    }
}
