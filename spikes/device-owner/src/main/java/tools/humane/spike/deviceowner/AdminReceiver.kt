package tools.humane.spike.deviceowner

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/** The admin component `dpm set-device-owner` names. It needs no behaviour of its own. */
class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
