package com.ethran.notable.utils

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import kotlin.math.abs

/**
 * Decides when the screen should follow the device's tilt. It turns less
 * eagerly than Android's own auto-rotate, which flips at roughly 45° after a
 * few dozen milliseconds and has no setting to change that.
 *
 * A turn needs all three of:
 * - the device held within [captureDegrees] of another orientation's upright
 *   position, so it is at least 90° − [captureDegrees] away from the current one;
 * - that holding continuously for [settleMs];
 * - no pen or finger input for [quietAfterInputMs]. Resting a hand on the
 *   device or writing at an angle is what makes the system flip mid-stroke.
 *
 * Rotations are `Surface.ROTATION_*` values (0..3, quarter turns of the
 * display from the device's natural orientation). Pure logic; time is passed in.
 */
class RotationSteadier(
    initialRotation: Int,
    private val captureDegrees: Int = 25,
    private val settleMs: Long = 800,
    private val quietAfterInputMs: Long = 1500,
) {
    var rotation: Int = initialRotation
        private set

    private var candidate: Int? = null
    private var candidateSince = 0L
    // Written from the pen's input thread, read on the main thread.
    @Volatile
    private var lastInputAt = Long.MIN_VALUE / 2

    /** Pen or finger activity: holds the current orientation for a while. Any thread. */
    fun onInput(nowMs: Long) {
        lastInputAt = nowMs
    }

    /** The screen was turned some other way, e.g. by the system before we took over. */
    fun resetTo(rotation: Int) {
        this.rotation = rotation
        candidate = null
    }

    /**
     * Feeds one sensor reading: [tiltDegrees] as reported by
     * [OrientationEventListener] (clockwise device rotation, 0..359), or
     * [OrientationEventListener.ORIENTATION_UNKNOWN] when lying flat.
     * Returns the new rotation when the screen should turn, otherwise null.
     */
    fun onTilt(tiltDegrees: Int, nowMs: Long): Int? {
        val target = if (tiltDegrees < 0) null else captured(tiltDegrees)
        if (target == null || target == rotation || nowMs - lastInputAt < quietAfterInputMs) {
            candidate = null
            return null
        }
        if (target != candidate) {
            candidate = target
            candidateSince = nowMs
            return null
        }
        if (nowMs - candidateSince < settleMs) return null
        rotation = target
        candidate = null
        return target
    }

    /** The rotation whose upright position [tiltDegrees] is within [captureDegrees] of. */
    private fun captured(tiltDegrees: Int): Int? {
        val quarter = ((tiltDegrees + 45) / 90) % 4
        val offset = abs(tiltDegrees - quarter * 90).let { if (it > 180) 360 - it else it }
        if (offset > captureDegrees) return null
        // Tilting the device clockwise turns the display the other way.
        return (4 - quarter) % 4
    }
}

/**
 * Applies [RotationSteadier] to an activity: while [start]ed and the system's
 * auto-rotate is on, the activity locks its orientation and turns it only when
 * the steadier says so. With auto-rotate off it leaves the orientation to the
 * system, so the system's rotation lock keeps working as before.
 *
 * Call [onInput] from the activity's touch and hover events, and
 * [SteadyRotationController.penActivity] from input that bypasses them (Onyx raw drawing).
 */
class SteadyRotationController(private val activity: Activity) {
    companion object {
        @Volatile
        private var active: SteadyRotationController? = null

        /** Pen input seen outside the activity's event dispatch. Any thread. */
        fun penActivity() {
            active?.onInput()
        }
    }

    private var started = false
    @Volatile
    private var steadier: RotationSteadier? = null
    private var listener: OrientationEventListener? = null

    private val autoRotateObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = applySystemSetting()
    }

    fun start() {
        if (started) return
        started = true
        active = this
        activity.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            autoRotateObserver,
        )
        applySystemSetting()
    }

    fun stop() {
        if (!started) return
        started = false
        if (active === this) active = null
        activity.contentResolver.unregisterContentObserver(autoRotateObserver)
        disableSensor()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun onInput() {
        steadier?.onInput(SystemClock.uptimeMillis())
    }

    private fun applySystemSetting() {
        val autoRotate = Settings.System.getInt(
            activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0
        ) == 1
        if (autoRotate) enableSensor() else {
            disableSensor()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun enableSensor() {
        if (listener != null) return
        val current = displayRotation()
        val s = RotationSteadier(current)
        steadier = s
        // Lock where we are, so the system's own auto-rotate stops turning us.
        activity.requestedOrientation = screenOrientationFor(current)
        val l = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(orientation: Int) {
                // Something else (e.g. the system's rotate button) may have turned us.
                if (displayRotation() != s.rotation) s.resetTo(displayRotation())
                val turn = s.onTilt(orientation, SystemClock.uptimeMillis()) ?: return
                activity.requestedOrientation = screenOrientationFor(turn)
            }
        }
        if (!l.canDetectOrientation()) {
            steadier = null
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        listener = l
        l.enable()
    }

    private fun disableSensor() {
        listener?.disable()
        listener = null
        steadier = null
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = activity.windowManager.defaultDisplay.rotation

    /**
     * `requestedOrientation` speaks portrait/landscape, not quarter turns, so
     * the mapping depends on whether the device is naturally portrait or landscape.
     */
    private fun screenOrientationFor(rotation: Int): Int {
        val sideways = displayRotation().let { it == Surface.ROTATION_90 || it == Surface.ROTATION_270 }
        val landscapeNow =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val naturallyPortrait = sideways == landscapeNow
        return if (naturallyPortrait) when (rotation) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else when (rotation) {
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}
