package com.zucham.qbsmarter.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class BleManager {
    actual val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED)
    actual val scannedDevices: StateFlow<List<BleDevice>> = MutableStateFlow(emptyList())
    actual val discoveredServices: StateFlow<List<BleService>> = MutableStateFlow(emptyList())
    actual val characteristicData: StateFlow<Map<String, ByteArray>> = MutableStateFlow(emptyMap())
    actual val notificationsReady: StateFlow<Boolean> = MutableStateFlow(false)

    actual fun scanForDevices() = throw NotImplementedError("TODO: webMain BleManager")
    actual fun stopScan() = Unit
    actual fun connectToDevice(device: BleDevice) =
        throw NotImplementedError("TODO: webMain BleManager")
    actual fun disconnect() = Unit
    actual fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, data: ByteArray) =
        throw NotImplementedError("TODO: webMain BleManager")
    actual fun enableNotifications(serviceUuid: String, characteristicUuid: String) =
        throw NotImplementedError("TODO: webMain BleManager")
    actual fun hasRequiredPermissions(): Boolean = false
    actual fun requiredRuntimePermissions(): Array<String> = emptyArray()
    actual fun isBluetoothEnabled(): Boolean = false
}
