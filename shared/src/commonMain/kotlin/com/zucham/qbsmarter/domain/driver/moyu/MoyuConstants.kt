package com.zucham.qbsmarter.domain.driver.moyu

/**
 * BLE protocol constants for the MoYu WeiLong V10 AI smart cube.
 *
 * The cube advertises a single primary service for normal app
 * communication (a separate OTA-update service exists but is not used
 * here – firmware update is the official WCU app's domain). Within the
 * main service, two characteristics are relevant:
 *
 *   • NOTIFY characteristic: the cube pushes state and event packets
 *     (move, facelets, gyro, battery, hardware info) via CCCD-enabled
 *     notifications.
 *   • WRITE characteristic: the app pushes 20-byte command packets
 *     (request info, request facelets, enable/disable gyro, etc.).
 *
 * All traffic is encrypted with [MoyuEncryptor]. UUIDs are taken from
 * the WeiLong V10 AI protocol writeup
 * (https://github.com/lukeburong/weilong-v10-ai-protocol).
 *
 * **Device-name detection.** Unlike GAN cubes (which we detect by MAC
 * OUI prefix in the Devices screen), MoYu V10 advertises itself with a
 * device-name prefix `WCU_MY` (e.g. `WCU_MY32_XXXX`). The MAC OUI is
 * not documented in the protocol writeup and likely varies, so the
 * device name is the more reliable surface-to-top heuristic.
 *
 * The actual connect-time vendor decision in
 * [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] uses the BLE
 * service UUIDs once service discovery completes, since name-based
 * matching would be vulnerable to spoofing or model variations.
 */
internal object MoyuConstants {

    const val SERVICE_UUID = "0783b03e-7735-b5a0-1760-a305d2795cb0"
    const val NOTIFY_CHAR_UUID = "0783b03e-7735-b5a0-1760-a305d2795cb1"
    const val WRITE_CHAR_UUID = "0783b03e-7735-b5a0-1760-a305d2795cb2"

    /**
     * Lowercase device-name prefix used by the WeiLong V10 AI when
     * advertising. Used by the Devices screen to surface MoYu cubes near
     * the top of the scan results. Match is case-insensitive.
     */
    const val DEVICE_NAME_PREFIX = "WCU_MY"
}

/**
 * Detection helper for the MoYu service UUID. Case-insensitive lookup
 * against a list of advertised service UUIDs from the platform's GATT
 * service-discovery callback.
 */
internal fun detectMoyuServices(advertisedServices: Iterable<String>): Boolean {
    val target = MoyuConstants.SERVICE_UUID.lowercase()
    return advertisedServices.any { it.lowercase() == target }
}

/**
 * Detection helper for the MoYu device-name prefix. Used by the Devices
 * screen pre-connect (the service UUIDs aren't available until after the
 * connect handshake completes). Case-insensitive prefix match.
 */
fun isMoyuCubeName(name: String?): Boolean =
    name?.startsWith(MoyuConstants.DEVICE_NAME_PREFIX, ignoreCase = true) == true
