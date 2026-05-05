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

    actual fun scanForDevices(): Unit = throw NotImplementedError("TODO: jvmMain BleManager")
    actual fun stopScan() = Unit
    actual fun connectToDevice(device: BleDevice): Unit =
        throw NotImplementedError("TODO: jvmMain BleManager")
    actual fun disconnect() = Unit
    actual fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, data: ByteArray): Unit =
        throw NotImplementedError("TODO: jvmMain BleManager")
    actual fun enableNotifications(serviceUuid: String, characteristicUuid: String): Unit =
        throw NotImplementedError("TODO: jvmMain BleManager")
    actual fun hasRequiredPermissions(): Boolean = false
    actual fun requiredRuntimePermissions(): Array<String> = emptyArray()
    actual fun isBluetoothEnabled(): Boolean = false
}
