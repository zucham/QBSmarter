package com.zucham.qbsmarter.util

/**
 * Open a URL in the platform's default browser / handler.
 */
interface UrlOpener {
    fun open(url: String)
}

/**
 * Inhibit the screen-off timer while in use. Bound to a real Activity on
 * Android via WindowManager flags; a no-op on other platforms.
 */
interface ScreenKeeper {
    fun setKeepScreenOn(enabled: Boolean)
}

/**
 * Cross-platform handle for sending the user to the OS's Bluetooth
 * settings panel. Used by the Devices screen when the BLE adapter is
 * disabled. On Android this fires
 * `Intent(Settings.ACTION_BLUETOOTH_SETTINGS)`; on platforms without a
 * comparable affordance the implementation is a no-op.
 *
 * Why a separate interface rather than reusing UrlOpener: a settings
 * panel intent isn't a URL – Android's `Settings.ACTION_*` constants
 * are intent action strings, not URIs. Modeling it explicitly keeps
 * the platform code honest about what's actually being launched.
 */
interface BluetoothSettings {
    fun openSettings()
}
