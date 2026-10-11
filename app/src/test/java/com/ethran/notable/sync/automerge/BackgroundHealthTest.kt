package com.ethran.notable.sync.automerge

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundHealthTest {
    private val ok = BackgroundSignals(
        backgroundRestricted = false, ignoringBatteryOptimizations = true,
        recentExits = emptyList(), onyxDevice = true,
    )

    @Test
    fun healthy_when_nothing_restricts_us() {
        assertEquals(BackgroundHealth.Ok, BackgroundHealth.of(ok))
    }

    @Test
    fun onyx_freeze_shows_as_a_package_state_change_kill() {
        // Onyx disables the package to freeze it; the system then kills us with "change <pkg>".
        val h = BackgroundHealth.of(ok.copy(recentExits = listOf(ExitKind.PACKAGE_STATE_CHANGE)))
        assertEquals(BackgroundHealth.FrozenByOnyx, h)
    }

    @Test
    fun package_state_kills_on_other_devices_are_not_called_onyx() {
        val h = BackgroundHealth.of(ok.copy(onyxDevice = false, recentExits = listOf(ExitKind.PACKAGE_STATE_CHANGE)))
        assertEquals(BackgroundHealth.KilledByPackageChange, h)
    }

    @Test
    fun background_restriction_is_reported() {
        assertEquals(BackgroundHealth.Restricted, BackgroundHealth.of(ok.copy(backgroundRestricted = true)))
    }

    @Test
    fun battery_optimization_is_a_softer_warning() {
        assertEquals(BackgroundHealth.BatteryOptimized, BackgroundHealth.of(ok.copy(ignoringBatteryOptimizations = false)))
    }

    @Test
    fun freezing_outranks_the_other_warnings() {
        val h = BackgroundHealth.of(
            ok.copy(backgroundRestricted = true, ignoringBatteryOptimizations = false, recentExits = listOf(ExitKind.OTHER, ExitKind.PACKAGE_STATE_CHANGE))
        )
        assertEquals(BackgroundHealth.FrozenByOnyx, h)
    }
}
