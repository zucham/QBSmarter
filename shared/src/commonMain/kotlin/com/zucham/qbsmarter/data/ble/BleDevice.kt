package com.zucham.qbsmarter.data.ble

/**
 * A device seen during a BLE scan, or a paired cube being reconnected.
 *
 * @property advertisedServiceUuids service UUIDs carried in the
 *   advertisement, lowercased. This is the *authoritative* pre-connect
 *   signal for what a device is — the same UUIDs the orchestrator
 *   matches on after service discovery, just available a step earlier.
 *   Often empty: a peripheral isn't obliged to advertise its services,
 *   and Android splits advertising and scan-response packets so the list
 *   can fill in a beat later. Treat empty as "no information", never as
 *   "not a cube".
 */
data class BleDevice(
    val name: String?,
    val address: String,
    val advertisedServiceUuids: List<String> = emptyList(),
)

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, PERMISSION_DENIED, BLUETOOTH_DISABLED, ERROR,
}

data class BleService(val uuid: String, val characteristics: List<BleCharacteristic>)
data class BleCharacteristic(val uuid: String, val properties: Int)
