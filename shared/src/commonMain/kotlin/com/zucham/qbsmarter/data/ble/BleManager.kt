package com.zucham.qbsmarter.data.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform BLE wrapper. Android uses BluetoothLeScanner + BluetoothGatt;
 * other platforms throw on use until they're implemented.
 */
expect class BleManager {
    val connectionState: StateFlow<ConnectionState>
    val scannedDevices: StateFlow<List<BleDevice>>
    val discoveredServices: StateFlow<List<BleService>>
    val characteristicData: StateFlow<Map<String, ByteArray>>

    /**
     * Flips true when the CCCD descriptor write succeeds – i.e. the cube
     * is ready to receive commands without racing against the descriptor
     * write. Reset to false on disconnect / new connection. Subscribers
     * (DevicesViewModel) gate post-connect commands on this so they don't
     * get silently dropped by the GATT queue.
     */
    val notificationsReady: StateFlow<Boolean>

    fun scanForDevices()
    fun stopScan()
    fun connectToDevice(device: BleDevice)
    fun disconnect()
    fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, data: ByteArray)
    fun enableNotifications(serviceUuid: String, characteristicUuid: String)
    fun hasRequiredPermissions(): Boolean

    /**
     * The runtime permissions the host activity should request when the
     * user opts to grant access. The exact set varies by API level, so
     * MainActivity calls this to fire a single-shot request without
     * duplicating the API-level branching logic.
     *
     * Stub platforms return an empty array.
     */
    fun requiredRuntimePermissions(): Array<String>

    /**
     * True when the host has BLE hardware AND the user has the system
     * Bluetooth toggle ON. On platforms without a runtime check this is
     * a stub that returns true.
     *
     * Devices screen reads this to decide whether to show the "Bluetooth
     * is disabled – enable it" CTA when the user taps Pair.
     */
    fun isBluetoothEnabled(): Boolean
}
