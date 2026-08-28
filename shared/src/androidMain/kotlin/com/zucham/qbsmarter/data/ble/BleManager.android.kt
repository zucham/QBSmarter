package com.zucham.qbsmarter.data.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android BLE wrapper.
 *
 * Permission model varies by API level:
 *   • API 31+ (Android 12+): BLUETOOTH_SCAN + BLUETOOTH_CONNECT, both
 *     declared with `usesPermissionFlags="neverForLocation"` so we don't
 *     also need ACCESS_FINE_LOCATION.
 *   • API 29-30 (Android 10/11): legacy BLUETOOTH + BLUETOOTH_ADMIN are
 *     declared in the manifest with `maxSdkVersion=30` so they're only
 *     applied on these versions, but those are normal-protection
 *     permissions (granted at install time). The runtime gate that
 *     matters here is ACCESS_FINE_LOCATION – without it BLE scan
 *     returns no results on Android 10/11 because the OS treats scan
 *     results as location data.
 *
 * Bluetooth-disabled handling: every entry-point that opens the radio
 * (scan, connect) checks [isBluetoothEnabled] first and flips the
 * connection state to [ConnectionState.BLUETOOTH_DISABLED] if not. The
 * Devices screen reacts to that state with an "Enable Bluetooth" CTA.
 */
actual class BleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    }
    private val bleScanner get() = bluetoothAdapter?.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    /**
     * Internal scope used only for the post-disconnect timeout in
     * [disconnect]. We can't use a viewModelScope or screen scope here
     * because BleManager is a long-lived singleton, but we DO need a
     * coroutine to schedule the fallback close after the
     * platform-callback wait. SupervisorJob so a stray failure doesn't
     * cancel future cleanup attempts; Dispatchers.Default because the
     * actual work (state-flow assignments + gatt.close()) is fast and
     * doesn't need Main.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    actual val connectionState = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    actual val scannedDevices = _scannedDevices.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<BleService>>(emptyList())
    actual val discoveredServices = _discoveredServices.asStateFlow()

    private val _characteristicData = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    actual val characteristicData = _characteristicData.asStateFlow()

    private val _notificationsReady = MutableStateFlow(false)
    actual val notificationsReady = _notificationsReady.asStateFlow()

    actual fun scanForDevices() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.BLUETOOTH_DISABLED
            return
        }
        // Defensive guard: refuse to start a scan while a GATT connection
        // is alive. The previous code transitioned _connectionState to
        // SCANNING regardless, which masked the still-open connection from
        // observers (they saw "not connected anymore") while the BLE link
        // was actually still up – a half-state that left the peripheral
        // confused. Callers that want to start a new scan from a connected
        // state must explicitly disconnect first and await the
        // STATE_DISCONNECTED callback (DevicesViewModel.startScan does this).
        if (bluetoothGatt != null) {
            Log.w(TAG, "scanForDevices: refusing to scan with live GATT (caller skipped disconnect-await)")
            return
        }
        // Even with BT enabled and perms granted, the scanner can be null
        // on devices that lack BLE hardware. The manifest declares
        // bluetooth_le as required so this should never happen on
        // installed-from-Play devices, but defensive bailout for
        // sideloaded installs.
        val scanner = bleScanner ?: run {
            _connectionState.value = ConnectionState.ERROR
            Log.w(TAG, "scanForDevices: no scanner (BLE hardware missing?)")
            return
        }
        _connectionState.value = ConnectionState.SCANNING
        _scannedDevices.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure {
                Log.e(TAG, "scanForDevices: startScan threw", it)
                _connectionState.value = ConnectionState.ERROR
            }
    }

    actual fun stopScan() {
        if (!hasScanPermission()) return
        val scanner = bleScanner ?: return
        runCatching { scanner.stopScan(scanCallback) }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    actual fun connectToDevice(device: BleDevice) {
        if (!hasRequiredPermissions()) {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.BLUETOOTH_DISABLED
            return
        }
        // Defensive guard: refuse to start a new connection if a previous
        // GATT is still alive. The orchestrator is supposed to drive any
        // existing connection through [disconnect] and wait for the
        // STATE_DISCONNECTED callback before calling here, so seeing a
        // non-null gatt here is a bug upstream – most likely a code path
        // that bypassed the orchestrator. If we just clobbered
        // bluetoothGatt with a fresh connectGatt(), the old GATT would
        // leak and the previous peripheral would be left thinking it's
        // still connected (same root cause as the Forget-while-connected
        // bug fixed earlier).
        if (bluetoothGatt != null) {
            Log.w(TAG, "connectToDevice: refusing to overwrite live GATT (caller skipped disconnect-await)")
            _connectionState.value = ConnectionState.ERROR
            return
        }
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        _notificationsReady.value = false
        // getRemoteDevice returns null when the adapter is missing or the
        // MAC is malformed; handle both.
        val androidDevice = runCatching {
            bluetoothAdapter?.getRemoteDevice(device.address)
        }.getOrNull()
        if (androidDevice == null) {
            Log.w(TAG, "connectToDevice: getRemoteDevice returned null for ${device.address}")
            _connectionState.value = ConnectionState.ERROR
            return
        }
        bluetoothGatt = runCatching { androidDevice.connectGatt(context, false, gattCallback) }
            .onFailure {
                Log.e(TAG, "connectToDevice: connectGatt threw", it)
                _connectionState.value = ConnectionState.ERROR
            }
            .getOrNull()
    }

    actual fun disconnect() {
        if (!hasConnectPermission()) return
        val gatt = bluetoothGatt ?: run {
            // Already torn down (or never connected). Make state
            // consistent – observers waiting on connectionState == DISCONNECTED
            // need to see the transition even when there's no GATT.
            _connectionState.value = ConnectionState.DISCONNECTED
            _discoveredServices.value = emptyList()
            _characteristicData.value = emptyMap()
            _notificationsReady.value = false
            return
        }
        // Two-stage teardown:
        //   1. gatt.disconnect() asks the Bluetooth stack to drop the link.
        //   2. The disconnect-state callback in [gattCallback] calls
        //      gatt.close() and clears bluetoothGatt.
        //
        // Calling close() *immediately* after disconnect() – which is what
        // earlier versions of this code did – is a documented Android
        // anti-pattern. The peripheral can be left thinking it's still
        // connected (so it doesn't go back into pairing-advertisement
        // mode and won't show up in scans on this OR any other phone)
        // until the user power-cycles its Bluetooth radio. The fix is to
        // wait for the BLE stack to finish its handshake.
        //
        // Belt-and-braces: schedule a fallback close in case the
        // STATE_DISCONNECTED callback never arrives (e.g. peripheral
        // out of range during disconnect, stack error). [DISCONNECT_TIMEOUT_MS]
        // is generous – typical disconnects complete in <100 ms, but
        // some flaky links can take longer.
        runCatching { gatt.disconnect() }
        cleanupScope.launch {
            delay(DISCONNECT_TIMEOUT_MS)
            // bluetoothGatt may have already been cleared by the callback –
            // identity check guards against closing a *different* GATT
            // instance if the user reconnected during the delay.
            if (bluetoothGatt === gatt) {
                Log.w(TAG, "disconnect: callback never fired, force-closing")
                runCatching { gatt.close() }
                bluetoothGatt = null
                _connectionState.value = ConnectionState.DISCONNECTED
                _discoveredServices.value = emptyList()
                _characteristicData.value = emptyMap()
                _notificationsReady.value = false
            }
        }
    }

    actual fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, data: ByteArray) {
        if (!hasConnectPermission()) return
        val characteristic = bluetoothGatt
            ?.getService(UUID.fromString(serviceUuid))
            ?.getCharacteristic(UUID.fromString(characteristicUuid))
        characteristic?.let {
            it.value = data
            runCatching { bluetoothGatt?.writeCharacteristic(it) }
        }
    }

    actual fun enableNotifications(serviceUuid: String, characteristicUuid: String) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "enableNotifications: no BLUETOOTH_CONNECT permission")
            return
        }
        val service = bluetoothGatt?.getService(UUID.fromString(serviceUuid))
        if (service == null) {
            Log.w(TAG, "enableNotifications: service $serviceUuid not found (services discovered yet?)")
            return
        }
        val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid))
        if (characteristic == null) {
            Log.w(TAG, "enableNotifications: char $characteristicUuid not found on service $serviceUuid")
            return
        }
        val ok = runCatching {
            bluetoothGatt?.setCharacteristicNotification(characteristic, true) ?: false
        }.getOrDefault(false)
        Log.d(TAG, "setCharacteristicNotification($characteristicUuid) -> $ok")

        val descriptor = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        )
        if (descriptor == null) {
            Log.w(TAG, "enableNotifications: CCCD descriptor missing on $characteristicUuid")
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val descriptorOk = runCatching { bluetoothGatt?.writeDescriptor(descriptor) ?: false }
            .getOrDefault(false)
        Log.d(TAG, "writeDescriptor(CCCD) -> $descriptorOk")
    }

    /**
     * Check that all currently-required runtime BLE permissions are granted.
     * The required set differs by API level:
     *   - Android 12+ (API 31): BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
     *   - Android 10/11 (API 29-30): ACCESS_FINE_LOCATION (the legacy
     *     BLUETOOTH/BLUETOOTH_ADMIN are install-time normal protections
     *     so they don't enter the runtime check).
     */
    actual fun hasRequiredPermissions(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            hasScanPermission() && hasConnectPermission()
        else ->
            checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    actual fun isBluetoothEnabled(): Boolean =
        bluetoothAdapter?.isEnabled == true

    /**
     * The runtime permissions the host activity should request when the
     * user opts to grant access. Surfaced for the host activity so it can
     * fire a single-shot request without duplicating the API-level
     * branching logic.
     */
    actual fun requiredRuntimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        else ->
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Convenience: scan-side permission for the active API level. */
    private fun hasScanPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            checkPerm(Manifest.permission.BLUETOOTH_SCAN)
        else ->
            checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Convenience: connect-side permission for the active API level. */
    private fun hasConnectPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
        else ->
            // On API < 31 there is no separate "connect" permission; the
            // legacy BLUETOOTH permission is normal-protection and
            // granted at install time. We treat it as always granted.
            true
    }

    private fun checkPerm(p: String): Boolean =
        ActivityCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // -- Scan callback --------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // BLUETOOTH_CONNECT is required to read device.name on API 31+;
            // on lower APIs the call always succeeds.
            if (!hasConnectPermission()) return
            val device = result.device
            val name = runCatching { device.name }.getOrNull()
            // Service UUIDs from the advertisement, when the peripheral
            // includes them. This is what lets the Devices screen
            // recognise a cube by protocol rather than by guessing from
            // its MAC or name.
            val services = result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString().lowercase() }
                .orEmpty()
            val ble = BleDevice(
                name = name,
                address = device.address,
                advertisedServiceUuids = services,
            )
            val current = _scannedDevices.value
            val existingIdx = current.indexOfFirst { it.address == ble.address }
            if (existingIdx < 0) {
                // First time we've seen this MAC in this scan session.
                _scannedDevices.value = current + ble
                Log.d(
                    TAG,
                    "Found ${ble.name ?: "Unknown"} (${ble.address}) services=$services",
                )
            } else {
                // Advertising data arrives in pieces: the first packet
                // often carries neither a name nor service UUIDs, with a
                // follow-up scan-response filling them in. Merge rather
                // than replace, so a late name doesn't discard services
                // we already saw (or vice versa). Without this a cube
                // would stay "Unknown", or unrecognised, forever.
                val existing = current[existingIdx]
                val mergedName = existing.name?.takeUnless { it.isBlank() } ?: name
                val mergedServices =
                    if (services.isNotEmpty()) services else existing.advertisedServiceUuids
                val merged = existing.copy(
                    name = mergedName,
                    advertisedServiceUuids = mergedServices,
                )
                if (merged != existing) {
                    _scannedDevices.value = current.toMutableList().also {
                        it[existingIdx] = merged
                    }
                    Log.d(
                        TAG,
                        "Updated ${ble.address}: name=${merged.name} " +
                            "services=${merged.advertisedServiceUuids}",
                    )
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.ERROR
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    // -- GATT callback --------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasConnectPermission()) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT")
                    _connectionState.value = ConnectionState.CONNECTED
                    bluetoothGatt = gatt
                    runCatching { gatt.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT")
                    // Close the GATT here, AFTER the platform finished its
                    // disconnect handshake. This is the documented
                    // correct ordering – closing earlier (synchronously
                    // after gatt.disconnect()) leaves the peripheral
                    // thinking it's still connected on some Android
                    // versions, breaking re-discovery without a BT
                    // power-cycle. See [disconnect] for the calling-side
                    // pair of this.
                    runCatching { gatt.close() }
                    bluetoothGatt = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _discoveredServices.value = emptyList()
                    _characteristicData.value = emptyMap()
                    _notificationsReady.value = false
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _discoveredServices.value = gatt.services.map { service ->
                    BleService(
                        uuid = service.uuid.toString(),
                        characteristics = service.characteristics.map { char ->
                            BleCharacteristic(
                                uuid = char.uuid.toString(),
                                properties = char.properties,
                            )
                        },
                    )
                }
                Log.d(TAG, "Discovered ${gatt.services.size} services")
            }
        }

        // ---------------------------------------------------------------
        // Both signatures of the characteristic callbacks are overridden.
        //
        // Android 13 (API 33) added a new variant of onCharacteristicRead /
        // onCharacteristicChanged / onDescriptorRead that takes the value
        // as an explicit ByteArray parameter (the old `characteristic.value`
        // accessor was deprecated for thread-safety reasons). The runtime
        // calls EXACTLY ONE of the variants depending on which the app
        // overrides AND on the platform version:
        //
        //   • API < 33 (Android 10/11/12): the framework only knows the
        //     deprecated 2-/3-parameter form. The new (4-parameter)
        //     variant is NEVER invoked, even if overridden – so an app
        //     that overrides only the new form will receive zero
        //     notifications on Android 12 and earlier. This was the
        //     root cause of the "connects but no moves" bug on Android 12.
        //
        //   • API >= 33: the framework prefers the new variant when both
        //     are overridden. The deprecated form is also still called
        //     for backwards compatibility with apps that didn't update.
        //
        // Solution: override BOTH. The deprecated variant reads
        // `characteristic.value` (deprecated but functional everywhere)
        // and routes through the same publishChange. The new variant
        // is the path taken on API 33+.

        // New (API 33+) variant – value is explicit. Preferred when
        // available because it avoids the data race inherent in
        // characteristic.value being a shared mutable buffer.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) publishChange(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            publishChange(characteristic, value)
        }

        // Deprecated (pre-API-33) variants – required for Android 10/11/12
        // because the framework on those versions never invokes the new
        // 4-parameter forms above. Reading characteristic.value is the
        // only way to get the payload on those platform versions.
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: return
                publishChange(characteristic, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            publishChange(characteristic, value)
        }

        // Defensive copy: BluetoothGattCharacteristic.value can be reused by
        // the framework, so subscribers must not see the live buffer.
        private fun publishChange(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid.toString()
            _characteristicData.value =
                _characteristicData.value.toMutableMap().also { it[uuid] = value.copyOf() }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "onDescriptorWrite ${descriptor.uuid} status=$status (0=success)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // The CCCD descriptor is the only descriptor we ever write,
                // so a successful write here means notifications are now
                // active and the cube will start emitting state packets.
                // From this point onward command-writes are safe.
                _notificationsReady.value = true
            }
        }
    }

    private companion object {
        const val TAG = "BleManager"

        /**
         * How long to wait for the BLE stack's disconnect-state callback
         * before force-closing the GATT. Typical disconnects complete in
         * <100 ms; this is a generous defensive bound for flaky links
         * (peripheral out of range, stack temporarily wedged). If the
         * callback fires before this elapses, the cleanup runs there
         * and this fallback no-ops.
         */
        const val DISCONNECT_TIMEOUT_MS = 1500L
    }
}
