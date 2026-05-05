package com.zucham.qbsmarter.data.ble

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.db.DevicesRepository
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.gan.GanCubeDriver
import com.zucham.qbsmarter.domain.driver.gan.GanEncryptor
import com.zucham.qbsmarter.domain.driver.gan.GanGeneration
import com.zucham.qbsmarter.domain.driver.gan.ganSaltFromMac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-lived owner of the connect-and-handshake flow. Lives as a Koin
 * singleton so per-screen VM lifecycles don't tear down a connection in
 * progress: a user who taps Connect on the Devices screen and immediately
 * navigates back to Solve still gets INFO + FACELETS + BATTERY delivered.
 *
 * Two concerns combined here on purpose:
 *   1. Initiating a connection = `connect(device)` does the BLE handshake,
 *      waits for service discovery + CCCD descriptor write, then issues the
 *      three startup commands.
 *   2. Persisting & surfacing INFO/BATTERY events. Hardware events update
 *      the cubes table; Battery events go into a transient StateFlow.
 *
 * Both have to live longer than any VM, so they share the same scope
 * (the app-wide singleton scope from Koin).
 */
class ConnectionOrchestrator(
    private val ble: BleManager,
    private val driver: GanCubeDriver,
    private val devicesRepo: DevicesRepository,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("ConnectionOrchestrator")

    /**
     * MAC of the cube currently being connected to or connected.
     *
     * Exposed as a flow so
     * the Devices screen can light up the right row's "Connecting…" UI
     * during the handshake. Set inside [connect]'s coroutine right
     * before the new BLE connect kicks off (so the row's spinner
     * appears as soon as `connectionState` flips to `CONNECTING`).
     * Cleared in [disconnect].
     *
     * Subtlety: when [connect] is switching from one cube to another,
     * the new MAC is **not** set until after the previous connection
     * has been fully torn down and acknowledged. Setting it earlier
     * would let the long-lived Hardware/Battery event handlers
     * (which read `_activeMac.value`) attribute trailing events from
     * the old cube to the new MAC.
     *
     * The MAC is the only stable identifier we have at the moment
     * connect is initiated (we don't yet know which paired-cube row
     * matches via id; the UI cross-references by MAC).
     */
    private val _activeMac = MutableStateFlow<String?>(null)
    val activeMac: StateFlow<String?> = _activeMac.asStateFlow()

    /**
     * Live battery levels keyed by cube MAC. Exposed for the Devices
     * screen's per-row indicator and for any other consumer that wants
     * to show "X%". Cleared on disconnect.
     */
    private val _batteryByMac = MutableStateFlow<Map<String, Int>>(emptyMap())
    val batteryByMac: StateFlow<Map<String, Int>> = _batteryByMac.asStateFlow()

    private var connectJob: Job? = null

    /**
     * Wall-clock timestamp of the most recent Facelets resync request.
     * Used to debounce: a burst of MovesMissed events during a flaky BLE
     * window shouldn't generate dozens of GATT writes – one resync covers
     * all of them.
     */
    private var lastResyncRequestMs: Long = 0L

    init {
        // Persist Hardware events to the DB; cache Battery in memory.
        // Both listeners live forever so they don't miss an event because
        // the user happened to be on a different screen at that moment.
        driver.events
            .onEach { event ->
                when (event) {
                    is SmartCubeEvent.Hardware -> _activeMac.value?.let { mac ->
                        log.d {
                            "INFO ($mac): hw=${event.hwVersion} sw=${event.swVersion} gyro=${event.gyroSupported}"
                        }
                        devicesRepo.updateHardwareInfo(
                            mac = mac,
                            hwVersion = event.hwVersion,
                            swVersion = event.swVersion,
                            gyroSupported = event.gyroSupported,
                        )
                    }
                    is SmartCubeEvent.Battery -> _activeMac.value?.let { mac ->
                        log.d { "BATTERY ($mac): ${event.level}%" }
                        _batteryByMac.value = _batteryByMac.value + (mac to event.level)
                    }
                    is SmartCubeEvent.MovesMissed -> {
                        // The parser saw a serial-number jump bigger than
                        // the cube's 7-move replay buffer, which means
                        // some moves were lost forever. The only way to
                        // recover the true state is a fresh Facelets
                        // snapshot. Debounced so a noisy BLE link doesn't
                        // generate a flood of GATT writes.
                        val now = event.deviceTimestamp
                        if (now - lastResyncRequestMs >= RESYNC_DEBOUNCE_MS) {
                            lastResyncRequestMs = now
                            log.w {
                                "MOVES MISSED (~${event.missedCount}): requesting facelets resync"
                            }
                            scope.launch {
                                runCatching { driver.send(SmartCubeCommand.RequestFacelets) }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    /**
     * Connect to [device] and run the post-connect handshake. The user can
     * navigate away mid-flow without breaking it – we run on the orchestrator's
     * own scope.
     *
     * **Existing-connection handling.** If there's already an active GATT
     * (because the user is jumping from cube A to cube B, or because a
     * previous connect attempt didn't finish unwinding), this method
     * tears it down cleanly first and waits for the BLE stack to
     * acknowledge before initiating the new connect. Without that
     * await, [BleManager.connectToDevice] would refuse the new connect
     * (its defensive guard rejects calls when `bluetoothGatt != null`)
     * and the user's tap would silently fail.
     *
     * If connect is already in flight from a previous call to this
     * method, the previous job is cancelled.
     */
    fun connect(device: BleDevice, userId: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            // Persist before connecting so a flaky connect still leaves a row.
            devicesRepo.rememberCube(userId, device.address, device.name)

            // If a previous BLE connection is alive (state CONNECTED) or
            // being established (state CONNECTING – connectGatt has run
            // but the handshake hasn't completed), tear it down and wait
            // for the platform to acknowledge before initiating the new
            // connect. Both states leave [BleManager.bluetoothGatt]
            // non-null, and [BleManager.connectToDevice]'s defensive
            // guard refuses if the GATT is alive – so without this
            // teardown the new connect would silently fail. This is the
            // central enforcement point; every callable path that asks
            // to connect a new cube routes through here. See
            // [BleManager.disconnect] for the close-ordering details
            // and [BleManager.connectToDevice] for the defensive guard.
            //
            // Note: we deliberately do this BEFORE setting [_activeMac]
            // to the new device. While the previous cube is still on
            // the wire, any in-flight Hardware/Battery events from it
            // could otherwise be attributed to the new MAC by the
            // long-lived handlers that read `_activeMac.value`.
            val priorState = ble.connectionState.value
            if (priorState == ConnectionState.CONNECTED || priorState == ConnectionState.CONNECTING) {
                log.d { "connect: tearing down previous connection (state=$priorState) before reconnecting" }
                ble.disconnect()
                runCatching {
                    withTimeout(EXISTING_CONNECTION_TIMEOUT_MS) {
                        ble.connectionState.first { it == ConnectionState.DISCONNECTED }
                    }
                }
                // If the wait timed out, BleManager's own 1.5 s
                // force-close fallback has already fired. Either way
                // we proceed: connectToDevice's own guard will refuse
                // and surface ERROR if the GATT is somehow still alive.
            }

            // Set the active-MAC flow immediately before the new BLE
            // connect kicks off so the Devices screen can light up the
            // right "Connecting…" row the instant `connectToDevice`
            // flips state to CONNECTING. The flow drives a per-row
            // spinner.
            _activeMac.value = device.address

            val encryptor = GanEncryptor(ganSaltFromMac(device.address))
            driver.disconnect()
            ble.connectToDevice(device)

            // CRITICAL ordering, four steps:
            //   1. Wait for service discovery – connectGatt() returns
            //      immediately and we can't write before discovery
            //      finishes.
            //   2. **Detect the cube's protocol generation** by
            //      matching the advertised service UUIDs against the
            //      known Gen2/3/4 service UUIDs. The encryption key/IV
            //      are identical across generations (per the upstream
            //      gan-web-bluetooth definitions file), so the
            //      encryptor we built above works for any generation –
            //      only the BLE characteristic UUIDs and the parser
            //      packet format differ.
            //   3. Build the transport with the matching service +
            //      characteristic UUIDs and call driver.connect(...,
            //      generation). That kicks off the CCCD descriptor
            //      write and selects the matching parser inside the
            //      driver.
            //   4. Wait for the descriptor write to complete
            //      (notificationsReady) BEFORE issuing INFO/FACELETS/
            //      BATTERY. Without this gate the command writes race
            //      against the descriptor write and the cube either
            //      drops them or replies into a void.
            // Detect generation from the cube's advertised services. We
            // collect on the discoveredServices flow until a snapshot
            // contains a service UUID matching one of the known
            // generations. The non-null assertion at the end is safe –
            // the predicate already verified `detect` returns non-null.
            val detectedGeneration: GanGeneration = run {
                lateinit var detected: GanGeneration
                ble.discoveredServices.first { services ->
                    val match = GanGeneration.detect(services.map { it.uuid })
                    if (match != null) {
                        detected = match
                        true
                    } else false
                }
                detected
            }
            log.d { "Detected GAN protocol $detectedGeneration for ${device.address}" }

            val transport = BleCubeTransport(
                ble = ble,
                serviceUuid = detectedGeneration.serviceUuid,
                commandCharUuid = detectedGeneration.commandCharUuid,
                stateCharUuid = detectedGeneration.stateCharUuid,
            )
            driver.connect(transport, encryptor, detectedGeneration)

            val ready = withTimeoutOrNull(NOTIFICATIONS_READY_TIMEOUT_MS) {
                ble.notificationsReady.first { it }
            }
            if (ready == null) {
                log.w { "Notifications never became ready; sending commands anyway" }
            }

            // Spaced commands so back-to-back GATT writes don't overflow
            // the queue on flaky stacks. Each is best-effort – failures
            // don't tear down the connection.
            runCatching { driver.send(SmartCubeCommand.RequestHardware) }
            delay(POST_CONNECT_GAP_MS)
            runCatching { driver.send(SmartCubeCommand.RequestFacelets) }
            delay(POST_CONNECT_GAP_MS)
            runCatching { driver.send(SmartCubeCommand.RequestBattery) }
        }
    }

    /**
     * Tear down the current connection. Safe to call at any point during
     * the lifecycle of [connect]:
     *
     *   • If the connect job is mid-handshake (waiting on service
     *     discovery, on `notificationsReady`, or in the spaced
     *     RequestHardware/Facelets/Battery writes), cancelling it
     *     interrupts the suspend points cleanly. We then **await the
     *     BLE stack acknowledging the disconnect** before clearing
     *     `_activeMac`. Without that wait, a subsequent user action
     *     (Pair new, switch profile, scan again) would race the in-
     *     flight teardown – exactly the family of bugs the close-
     *     ordering fix in [BleManager.disconnect] was meant to avoid.
     *   • If no connection is in flight, the BLE state is already
     *     DISCONNECTED and the await resolves immediately.
     *   • If the BLE stack hangs (out-of-range cube, wedged radio),
     *     the wait times out after [DISCONNECT_AWAIT_TIMEOUT_MS] and
     *     we proceed with the local cleanup anyway. BleManager's own
     *     1.5 s force-close fallback handles the radio side.
     *
     * Battery cleanup happens regardless of whether the await succeeded:
     * a stale "X%" indicator on a disconnected cube is more confusing
     * than no indicator. Same for `_activeMac`.
     */
    fun disconnect() {
        scope.launch {
            connectJob?.cancel()
            connectJob = null
            // Best-effort driver-level cleanup. If `disconnect()` itself
            // throws (shouldn't, but defensive), we still proceed to
            // tear down the BLE side.
            runCatching { driver.disconnect() }
            ble.disconnect()
            // Await the BLE stack acknowledging the disconnect. The
            // outer 2 s timeout matches the upstream waits in
            // DevicesViewModel.forget / startScan; under normal
            // conditions BleManager's internal 1.5 s fallback resolves
            // first, well before this.
            runCatching {
                withTimeout(DISCONNECT_AWAIT_TIMEOUT_MS) {
                    ble.connectionState.first { it == ConnectionState.DISCONNECTED }
                }
            }
            _activeMac.value?.let { _batteryByMac.value = _batteryByMac.value - it }
            _activeMac.value = null
        }
    }

    private companion object {
        // BLE service / characteristic UUIDs come from the auto-detected [GanGeneration]
        // in [connect]. See [GanGeneration] for the per-generation values.

        const val POST_CONNECT_GAP_MS = 120L
        const val NOTIFICATIONS_READY_TIMEOUT_MS = 3000L

        /**
         * Upper bound on how long [connect] waits for an existing GATT
         * link to fully tear down before initiating the new connect.
         * Slightly longer than [BleManager]'s own internal disconnect
         * timeout (1500 ms) so under normal conditions BleManager's
         * fallback resolves the state-flow first; this is the outer
         * safety net. If the wait times out, [connect] proceeds –
         * BleManager.connectToDevice's own defensive guard refuses if
         * the GATT is somehow still alive and surfaces ERROR.
         */
        const val EXISTING_CONNECTION_TIMEOUT_MS = 2000L

        /**
         * Upper bound on how long [disconnect] waits for the BLE stack
         * to acknowledge teardown after we've cancelled the connect
         * job and called `ble.disconnect()`. Same 2 s outer bound as
         * the existing-connection wait above, for the same reason:
         * BleManager's 1.5 s internal fallback resolves the state
         * flow first under normal conditions; this is the safety net
         * for when the radio is wedged or the cube is out of range.
         * Past this we proceed with local cleanup anyway – a wedged
         * radio is no longer recoverable from this layer, and the
         * caller (user-initiated cancel) needs the orchestrator state
         * to converge.
         */
        const val DISCONNECT_AWAIT_TIMEOUT_MS = 2000L

        /**
         * Minimum interval between auto-Facelets resync requests. A noisy
         * BLE window can produce a chain of MovesMissed events; without
         * debouncing we'd issue a GATT write for each, queueing up a flood
         * of duplicate Facelets responses. 1500 ms is generous: the cube
         * usually replies to a Facelets request within ~150ms, so any
         * MovesMissed received afterward represents new data warranting
         * another resync.
         */
        const val RESYNC_DEBOUNCE_MS = 1500L
    }
}
