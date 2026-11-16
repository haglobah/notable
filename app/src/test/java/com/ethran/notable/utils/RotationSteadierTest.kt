package com.ethran.notable.utils

import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationSteadierTest {

    /** Feeds the same tilt every 100 ms from [fromMs] to [toMs]; returns the last turn, if any. */
    private fun RotationSteadier.hold(tilt: Int, fromMs: Long, toMs: Long): Int? {
        var turned: Int? = null
        var t = fromMs
        while (t <= toMs) {
            onTilt(tilt, t)?.let { turned = it }
            t += 100
        }
        return turned
    }

    @Test
    fun turns_after_a_held_deliberate_tilt() {
        val s = RotationSteadier(Surface.ROTATION_0)
        assertNull(s.hold(tilt = 90, fromMs = 10_000, toMs = 10_700))
        assertEquals(Surface.ROTATION_270, s.hold(tilt = 90, fromMs = 10_800, toMs = 10_800))
        assertEquals(Surface.ROTATION_270, s.rotation)
    }

    @Test
    fun maps_tilt_direction_to_the_opposite_display_rotation() {
        val s = RotationSteadier(Surface.ROTATION_0)
        assertEquals(Surface.ROTATION_90, s.hold(tilt = 270, fromMs = 10_000, toMs = 11_000))
        assertEquals(Surface.ROTATION_180, s.hold(tilt = 180, fromMs = 12_000, toMs = 13_000))
        assertEquals(Surface.ROTATION_0, s.hold(tilt = 355, fromMs = 14_000, toMs = 15_000))
    }

    @Test
    fun ignores_tilts_between_orientations() {
        val s = RotationSteadier(Surface.ROTATION_0)
        // 50° is past the system's ~45° flip point but outside the 25° capture.
        assertNull(s.hold(tilt = 50, fromMs = 10_000, toMs = 20_000))
        assertNull(s.hold(tilt = 310, fromMs = 20_100, toMs = 30_000))
        assertEquals(Surface.ROTATION_0, s.rotation)
    }

    @Test
    fun a_wobble_restarts_the_settle_time() {
        val s = RotationSteadier(Surface.ROTATION_0)
        assertNull(s.hold(tilt = 90, fromMs = 10_000, toMs = 10_500))
        assertNull(s.onTilt(40, 10_600))
        assertNull(s.hold(tilt = 90, fromMs = 10_700, toMs = 11_400))
        assertEquals(Surface.ROTATION_270, s.hold(tilt = 90, fromMs = 11_500, toMs = 11_500))
    }

    @Test
    fun lying_flat_cancels_a_pending_turn() {
        val s = RotationSteadier(Surface.ROTATION_0)
        assertNull(s.hold(tilt = 90, fromMs = 10_000, toMs = 10_500))
        assertNull(s.onTilt(OrientationEventListener.ORIENTATION_UNKNOWN, 10_600))
        assertNull(s.hold(tilt = 90, fromMs = 10_700, toMs = 11_400))
    }

    @Test
    fun recent_input_holds_the_orientation() {
        val s = RotationSteadier(Surface.ROTATION_0)
        s.onInput(10_000)
        assertNull(s.hold(tilt = 90, fromMs = 10_000, toMs = 11_400))
        // Quiet from 11_500 on; then the full settle time is still needed.
        assertNull(s.hold(tilt = 90, fromMs = 11_500, toMs = 12_200))
        assertEquals(Surface.ROTATION_270, s.hold(tilt = 90, fromMs = 12_300, toMs = 12_300))
    }

    @Test
    fun reset_follows_a_turn_made_elsewhere() {
        val s = RotationSteadier(Surface.ROTATION_0)
        s.resetTo(Surface.ROTATION_270)
        assertNull(s.hold(tilt = 90, fromMs = 10_000, toMs = 12_000))
    }
}
