package tools.humane.spike.deviceowner

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen of buttons, each calling one device-owner API and logging what came back. Answers:
 * does the Boox let a device owner disable the status bar (notification shade), hide or suspend
 * apps, and pin a home app? See README.md for the checklist.
 */
class SpikeActivity : Activity() {
    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    private val admin by lazy { AdminReceiver.component(this) }
    private val homeAlias by lazy { ComponentName(this, "$packageName.HomeAlias") }

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var packageField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = text(column, size = 18f)

        heading(column, "Status bar (notification shade)")
        row(column, "Disable" to { statusBar(disabled = true) }, "Enable" to { statusBar(disabled = false) })

        heading(column, "Apps")
        packageField = EditText(this).apply {
            hint = "package, e.g. com.onyx.appmarket"
            textSize = 18f
            setSingleLine()
        }
        column.addView(packageField)
        row(column, "Hide" to { hide(true) }, "Unhide" to { hide(false) })
        row(column, "Suspend" to { suspend(true) }, "Unsuspend" to { suspend(false) })
        row(column, "List Onyx packages" to ::listOnyxPackages)

        heading(column, "Home")
        row(column, "Make spike Home" to { home(pin = true) }, "Clear Home" to { home(pin = false) })

        heading(column, "Undo")
        row(column, "Release device owner" to ::confirmRelease)

        heading(column, "Log")
        log = text(column, size = 15f)

        setContentView(ScrollView(this).apply { addView(column) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun refresh() {
        val owner = dpm.isDeviceOwnerApp(packageName)
        status.text = buildString {
            appendLine(if (owner) "Device owner: YES" else "Device owner: no (see README)")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            append("Firmware: ${Build.DISPLAY}")
        }
    }

    private fun statusBar(disabled: Boolean) = owned("setStatusBarDisabled($disabled)") {
        // false when the device has a secure lock screen, or the platform refuses.
        val ok = dpm.setStatusBarDisabled(admin, disabled)
        "returned $ok"
    }

    private fun hide(hidden: Boolean) = withPackage { pkg ->
        owned("setApplicationHidden($pkg, $hidden)") {
            val ok = dpm.setApplicationHidden(admin, pkg, hidden)
            "returned $ok; isApplicationHidden=${dpm.isApplicationHidden(admin, pkg)}"
        }
    }

    private fun suspend(suspended: Boolean) = withPackage { pkg ->
        owned("setPackagesSuspended($pkg, $suspended)") {
            val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), suspended)
            if (failed.isEmpty()) "ok" else "failed for ${failed.joinToString()}"
        }
    }

    private fun listOnyxPackages() {
        val names = packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }
            .filter { "onyx" in it || "boox" in it }
            .sorted()
        say("${names.size} Onyx/Boox packages:\n" + names.joinToString("\n"))
    }

    /**
     * Pins the spike as the home app, the way a device-owner launcher would. Tests whether the
     * Boox's own home handling respects a persistent preferred activity.
     */
    private fun home(pin: Boolean) = owned(if (pin) "pin Home" else "clear Home") {
        if (pin) {
            setAliasEnabled(true)
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, homeAlias)
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
            setAliasEnabled(false)
        }
        val resolved = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo
        "Home now resolves to ${resolved?.packageName}/${resolved?.name}"
    }

    private fun setAliasEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            homeAlias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setMessage("Turn the status bar back on, clear Home, and stop being device owner?")
            .setPositiveButton("Release") { _, _ -> release() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Undoes every policy first, so nothing outlives the device owner. */
    private fun release() = owned("release") {
        dpm.setStatusBarDisabled(admin, false)
        dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        setAliasEnabled(false)
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(packageName)
        refresh()
        "device owner now ${dpm.isDeviceOwnerApp(packageName)}"
    }

    private fun withPackage(action: (String) -> Unit) {
        val pkg = packageField.text.toString().trim()
        if (pkg.isEmpty()) say("Enter a package name first") else action(pkg)
    }

    /** Runs a device-owner call and logs its result or the exception the platform threw. */
    private fun owned(label: String, call: () -> String) {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            say("$label: not device owner")
            return
        }
        val result = try {
            call()
        } catch (e: Exception) {
            "threw ${e.javaClass.simpleName}: ${e.message}"
        }
        say("$label: $result")
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        log.text = "$time  $line\n\n${log.text}"
    }

    private fun text(parent: LinearLayout, size: Float) = TextView(this).apply {
        textSize = size
        setTextIsSelectable(true)
        parent.addView(this)
    }

    private fun heading(parent: LinearLayout, title: String) {
        TextView(this).apply {
            text = title
            textSize = 20f
            setPadding(0, 40, 0, 8)
            parent.addView(this)
        }
    }

    private fun row(parent: LinearLayout, vararg buttons: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((title, action) in buttons) {
            row.addView(
                Button(this).apply {
                    text = title
                    textSize = 18f
                    isAllCaps = false
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        parent.addView(row)
        parent.addView(View(this), LinearLayout.LayoutParams(1, 8))
    }

    private companion object {
        const val TAG = "DeviceOwnerSpike"
    }
}
