package com.ethran.notable.sync.automerge

/** Why the process last ended, reduced to what matters for background sync. */
enum class ExitKind { PACKAGE_STATE_CHANGE, OTHER }

/** What Android tells us about our ability to run in the background. */
data class BackgroundSignals(
    val backgroundRestricted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val recentExits: List<ExitKind>,
    val onyxDevice: Boolean,
)

/**
 * Whether background sync can work. Onyx's auto-freeze disables the whole package once the app
 * leaves the foreground (seen in Phase 0: `pm` state enabled=3, then a kill with reason
 * "change <pkg>"). Android reports that kill as a package-state change. A frozen app syncs only
 * while it's open, so the sync settings must say so instead of failing silently.
 */
sealed interface BackgroundHealth {
    data object Ok : BackgroundHealth
    data object FrozenByOnyx : BackgroundHealth
    data object KilledByPackageChange : BackgroundHealth
    data object Restricted : BackgroundHealth
    data object BatteryOptimized : BackgroundHealth

    companion object {
        fun of(s: BackgroundSignals): BackgroundHealth = when {
            ExitKind.PACKAGE_STATE_CHANGE in s.recentExits ->
                if (s.onyxDevice) FrozenByOnyx else KilledByPackageChange
            s.backgroundRestricted -> Restricted
            !s.ignoringBatteryOptimizations -> BatteryOptimized
            else -> Ok
        }
    }
}
