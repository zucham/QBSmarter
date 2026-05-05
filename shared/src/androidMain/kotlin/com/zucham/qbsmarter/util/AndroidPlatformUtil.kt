package com.zucham.qbsmarter.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import java.lang.ref.WeakReference

/** Opens URLs (incl. mailto:) via Intent.ACTION_VIEW. */
class AndroidUrlOpener(private val context: Context) : UrlOpener {
    override fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Inhibits screen-off via WindowManager flags. Must be bound to a real
 * Activity at runtime (in MainActivity.onCreate); we hold a WeakReference
 * so we don't leak the activity if the user kills the app.
 */
class AndroidScreenKeeper : ScreenKeeper {
    private var ref = WeakReference<Activity>(null)

    fun bind(activity: Activity) { ref = WeakReference(activity) }
    fun unbind(activity: Activity) {
        if (ref.get() === activity) ref = WeakReference<Activity>(null)
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        val activity = ref.get() ?: return
        activity.runOnUiThread {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/**
 * Sends the user to the system Bluetooth settings panel via
 * `Settings.ACTION_BLUETOOTH_SETTINGS`. Used by the Devices screen when
 * the user taps "Enable Bluetooth".
 *
 * We intentionally do NOT use the older `BluetoothAdapter.ACTION_REQUEST_ENABLE`
 * intent because it requires the BLUETOOTH_CONNECT permission on API 31+
 * (which the user may have just declined) and silently no-ops if not
 * granted. The settings panel route works regardless of permission state
 * and gives the user direct visibility into the toggle.
 */
class AndroidBluetoothSettings(private val context: Context) : BluetoothSettings {
    override fun openSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
