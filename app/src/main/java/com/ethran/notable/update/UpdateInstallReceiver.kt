package com.ethran.notable.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import io.shipbook.shipbooksdk.ShipBook
import javax.inject.Inject

private val log = ShipBook.getLogger("UpdateInstall")

/** The package installer's answer to [AppUpdates.install]: show its confirm screen, or report a failure. */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject
    lateinit var updates: AppUpdates

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) updates.installFailed("no confirm screen")
                else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            PackageInstaller.STATUS_SUCCESS -> log.i("update installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                log.w("update not installed: $message")
                updates.installFailed(message)
            }
        }
    }
}
