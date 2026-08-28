package com.zucham.qbsmarter.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zucham.qbsmarter.data.ble.BleDevice
import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.data.ble.ConnectionOrchestrator
import com.zucham.qbsmarter.data.ble.ConnectionState
import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.DevicesRepository
import com.zucham.qbsmarter.data.db.PairedCube
import com.zucham.qbsmarter.data.profile.ActiveProfile
import com.zucham.qbsmarter.util.BluetoothSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Pairing/connection state for the Devices screen. The actual
 * connect-and-handshake logic lives in [ConnectionOrchestrator] (a Koin
 * singleton on the long-lived app scope) so that navigating away mid-
 * connection doesn't tear it down. This VM is just a screen-scoped facade
 * that exposes the orchestrator's state to compose and forwards user
 * actions back to it.
 *
 * Profile-aware: [pairedCubes] tracks the active profile via [AppCache].
 * If the user switches profiles while on this screen, the list updates.
 * `pair()` and `reconnect()` resolve the active userId at call-time so
 * the cube is registered to whoever is active at that moment.
 *
 * Bluetooth-disabled handling: [startScan] / [pair] / [reconnect]
 * delegate to BleManager which flips [connectionState] to BLUETOOTH_DISABLED
 * when the user has BT off. The screen reads that state and offers an
 * "Enable Bluetooth" CTA which fires [openBluetoothSettings].
 */
class DevicesViewModel(
    private val ble: BleManager,
    private val orchestrator: ConnectionOrchestrator,
    private val devicesRepo: DevicesRepository,
    private val activeProfile: ActiveProfile,
    private val bluetoothSettings: BluetoothSettings,
    private val cache: AppCache,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = ble.connectionState
    val scannedDevices: StateFlow<List<BleDevice>> = ble.scannedDevices

    /** Paired cubes for the active profile. Sourced from the cache. */
    val pairedCubes: StateFlow<List<PairedCube>> = cache.pairedCubes

    /**
     * Identity of the currently connected paired cube, or null when
     * nothing is connected. Drives the green dot and accent border on
     * the matching row.
     *
     * Resolved by MAC from [ConnectionOrchestrator.activeMac], the
     * authoritative record of which cube is on the wire. This used to
     * guess "whichever paired cube was seen most recently", on the
     * theory that connecting refreshes `last_seen` and floats the right
     * row to the head of the list. That guess breaks whenever the
     * ordering doesn't cooperate: the cube connects fine and no row
     * lights up, or worse, the wrong row does.
     *
     * No `firstOrNull()` fallback, deliberately: an unlit row while the
     * MAC is still propagating is a momentary blank, whereas lighting up
     * a row we're only guessing at misreports which cube the app is
     * talking to.
     */
    val connectedCubeId: StateFlow<String?> =
        combine(pairedCubes, connectionState, orchestrator.activeMac) { paired, state, mac ->
            if (state != ConnectionState.CONNECTED || mac == null) null
            else paired.firstOrNull { it.mac == mac }?.id
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * MAC address of the cube currently being connected to (handshake in
     * flight), or null if no connection is being attempted right now.
     *
     * Sourced from [ConnectionOrchestrator.activeMac] gated on
     * [ConnectionState.CONNECTING]: the orchestrator sets `activeMac`
     * the instant `connect(...)` is invoked, so the flow flips on
     * before the GATT/service-discovery work begins. Once the handshake
     * completes (state → CONNECTED) we stop reporting it as
     * "connecting" – the row's appearance switches from spinner to the
     * green dot driven by [connectedCubeId].
     *
     * Used by the per-row paired-cube UI to show "Connecting…" on the
     * specific row being connected to and to disable other rows'
     * Connect buttons during the handshake (only one connect can be in
     * flight at a time).
     */
    val connectingMac: StateFlow<String?> =
        orchestrator.activeMac.combine(connectionState) { mac, state ->
            if (state == ConnectionState.CONNECTING) mac else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live battery levels (delegated to the orchestrator's long-lived map). */
    val batteryByMac: StateFlow<Map<String, Int>> = orchestrator.batteryByMac

    /** True when the user tried to scan/pair without granting BLE perms. */
    private val _missingPermissions = MutableStateFlow(false)
    val missingPermissions: StateFlow<Boolean> = _missingPermissions.asStateFlow()

    /**
     * True when the user tried to scan/pair while Bluetooth is disabled.
     * The screen reacts by showing the "Enable Bluetooth" CTA.
     *
     * Derived from [connectionState] reaching BLUETOOTH_DISABLED rather
     * than tracked separately; this collapses both "user just tapped
     * Pair while BT off" and "we noticed BT is off via some other path"
     * into one observable.
     */
    val bluetoothDisabled: StateFlow<Boolean> = connectionState
        .map { it == ConnectionState.BLUETOOTH_DISABLED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Begin scanning for nearby cubes.
     *
     * If a cube is currently connected (the user tapped "Pair new" from
     * the connected state), tear down the current connection FIRST and
     * wait for the BLE stack to acknowledge before starting the scan.
     * Without this, the previous code transitioned the connection-state
     * flow to `SCANNING` while leaving the GATT link alive – observers
     * thought "no longer connected" but the cube's firmware was still
     * holding the link open, putting the peripheral into a half-state
     * that took a Bluetooth power-cycle to recover from.
     *
     * The connect/disconnect sequencing matches what [forget] does for
     * the same reason: a fully-clean Android-side teardown is the only
     * way to get the cube into a re-pairable state quickly. See the
     * matching close-ordering fix in [BleManager.disconnect].
     */
    fun startScan() {
        if (!ble.hasRequiredPermissions()) {
            _missingPermissions.value = true
            return
        }
        _missingPermissions.value = false

        // The slow path triggers whenever a connection is alive or being
        // established – both states leave [BleManager.bluetoothGatt]
        // non-null, which would cause the BleManager-level defensive
        // guard in [BleManager.scanForDevices] to refuse the scan. By
        // routing through the orchestrator's [disconnect] (which cancels
        // any in-flight connect-job AND tears down the GATT) we cover
        // both cases uniformly.
        val state = connectionState.value
        val needsTeardown =
            state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING
        if (!needsTeardown) {
            // Fast path: nothing to disconnect, scan immediately. BleManager
            // itself flips state to BLUETOOTH_DISABLED if needed, so we
            // don't need a pre-check here – the UI will react via the
            // connectionState flow.
            ble.scanForDevices()
            return
        }
        // Slow path: there's a live or in-flight GATT. Disconnect cleanly,
        // wait for the platform to acknowledge, then scan.
        viewModelScope.launch {
            orchestrator.disconnect()
            try {
                withTimeout(SCAN_DISCONNECT_TIMEOUT_MS) {
                    ble.connectionState.first { it == ConnectionState.DISCONNECTED }
                }
            } catch (_: TimeoutCancellationException) {
                // Force-close fallback inside BleManager has already fired
                // by now (1.5 s) – proceed to scan anyway. A stuck-open
                // connection at this point isn't recoverable from the VM
                // layer and the user's intent ("scan for new") wins.
            }
            ble.scanForDevices()
        }
    }

    fun cancelScan() = ble.stopScan()

    fun pair(device: BleDevice) {
        val uid = activeProfile.idSnapshot() ?: return
        orchestrator.connect(device, uid)
    }

    fun reconnect(cube: PairedCube) {
        val uid = activeProfile.idSnapshot() ?: return
        orchestrator.connect(BleDevice(cube.name, cube.mac), uid)
    }

    /**
     * Forget a paired cube. If the cube being forgotten is the one
     * currently on the wire, **fully disconnect first and wait for the
     * BLE stack to acknowledge the teardown** before deleting the DB
     * row. Two reasons:
     *
     *   1. Leaving the BLE link active while removing the DB row leads
     *      to weird states – the orchestrator's `activeMac` still
     *      points at the now-deleted row, the connection indicator may
     *      stay green, and the next pair attempt may find phantom GATT
     *      state.
     *   2. **The peripheral itself.** Calling `gatt.close()` before the
     *      Android BLE stack has finished its disconnect handshake
     *      leaves some peripherals (the GAN cube included) thinking
     *      they're still connected – they don't return to advertising
     *      mode and won't show up in scans until the user power-cycles
     *      Bluetooth. The fix lives in [BleManager.disconnect], which
     *      defers `gatt.close()` to the `STATE_DISCONNECTED` callback.
     *      Here we just need to **wait** for that callback to fire
     *      before deleting the DB row, so the user never observes the
     *      racy intermediate state.
     *
     * The await is on [BleManager.connectionState] reaching
     * `DISCONNECTED`, with a timeout that matches the BLE manager's
     * own fallback so we never hang forever.
     *
     * The "currently connected" check uses [connectedCubeId]: while
     * BLE is in CONNECTED state, the most-recently-seen paired cube
     * is treated as the active one. If no cube is connected, we skip
     * straight to the DB delete – there's nothing to tear down.
     */
    fun forget(id: String) {
        if (connectedCubeId.value != id) {
            // Fast path: not the active cube, just drop the row.
            devicesRepo.forget(id)
            return
        }
        viewModelScope.launch {
            orchestrator.disconnect()
            // Wait for the BLE stack to fully unwind. The 2 s ceiling
            // is a defensive bound – BleManager's own fallback fires at
            // 1.5 s, so under normal conditions we resolve well before
            // this. If we somehow time out here, we still drop the row
            // (a stuck-connected cube is a worse UX than a stale row).
            try {
                withTimeout(FORGET_DISCONNECT_TIMEOUT_MS) {
                    ble.connectionState.first { it == ConnectionState.DISCONNECTED }
                }
            } catch (_: TimeoutCancellationException) {
                // Fall through to delete anyway – see comment above.
            }
            devicesRepo.forget(id)
        }
    }

    /**
     * Rename a paired cube. A blank name clears the user's override and
     * lets the cube's own advertised name take over again — see
     * [DevicesRepository.rename].
     *
     * No connection work involved: the name lives only in our database,
     * so this is safe on a connected cube and takes effect immediately
     * (the paired list observes the table).
     */
    fun rename(cube: PairedCube, name: String) {
        devicesRepo.rename(cube.id, name)
    }

    fun disconnect() = orchestrator.disconnect()

    /** Send the user to the OS Bluetooth settings panel. */
    fun openBluetoothSettings() = bluetoothSettings.openSettings()

    private companion object {
        /**
         * Upper bound on how long [forget] waits for the BLE stack to
         * acknowledge disconnection before deleting the DB row anyway.
         * Slightly longer than [BleManager]'s own internal timeout
         * (1500 ms) so under normal conditions BleManager's fallback
         * resolves the state-flow first; this is the outer safety net.
         */
        const val FORGET_DISCONNECT_TIMEOUT_MS = 2000L

        /**
         * Same idea as [FORGET_DISCONNECT_TIMEOUT_MS], for [startScan]
         * when invoked from the CONNECTED state. We kick off a
         * disconnect, wait for the platform to acknowledge it, then
         * begin scanning. Same 2 s outer bound – past this we proceed
         * with the scan regardless (a stuck-open connection is no
         * longer recoverable from this layer; the user's "Pair new"
         * intent wins).
         */
        const val SCAN_DISCONNECT_TIMEOUT_MS = 2000L
    }
}
