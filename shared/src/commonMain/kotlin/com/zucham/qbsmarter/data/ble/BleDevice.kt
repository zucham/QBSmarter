package com.zucham.qbsmarter.data.ble

data class BleDevice(val name: String?, val address: String)

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED, PERMISSION_DENIED, BLUETOOTH_DISABLED, ERROR,
}

data class BleService(val uuid: String, val characteristics: List<BleCharacteristic>)
data class BleCharacteristic(val uuid: String, val properties: Int)
